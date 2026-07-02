package dk.trustworks.intranet.aggregates.invoice.services;

import dk.trustworks.intranet.aggregates.invoice.bonus.services.InvoiceBonusService;
import dk.trustworks.intranet.aggregates.invoice.model.Invoice;
import dk.trustworks.intranet.aggregates.invoice.model.InvoiceItem;
import dk.trustworks.intranet.aggregates.invoice.model.enums.InvoiceType;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.persistence.EntityManager;
import jakarta.persistence.LockModeType;
import jakarta.ws.rs.WebApplicationException;
import jakarta.ws.rs.core.Response;
import lombok.extern.jbosslog.JBossLog;

import java.util.List;

/**
 * Java-side credited measure for client-invoice credit notes, plus the fail-closed
 * over-credit guard that replaced the {@code ux_invoices_creditnote_for_uuid} unique
 * index (dropped in V386) as the race protection for concurrent credit notes.
 *
 * <p>The measure is Σ(hours×rate) ex-VAT over credit-note items, with the shared
 * {@link InvoiceBonusService#CREDITED_TOLERANCE_DKK 1.0 DKK} tolerance — the same
 * convention as {@link InvoiceBonusService#NOT_FULLY_CREDITED_SQL}. "Live" credit
 * notes are CREATED / QUEUED / PENDING_REVIEW (a DRAFT does not yet credit anything).
 *
 * <p>Only client {@code INVOICE} sources may carry multiple credit notes;
 * {@code INTERNAL} sources keep the strict 1:1 full-reversal model enforced in
 * {@link InvoiceService#createCreditNote}. This service therefore no-ops for
 * anything that is not a client credit note over an {@code INVOICE} source.
 */
@JBossLog
@ApplicationScoped
public class CreditNoteCoverageService {

    /** Live credit-note statuses: credit that exists, per the shared SQL measure. */
    private static final List<String> LIVE_CN_STATUSES = List.of("CREATED", "QUEUED", "PENDING_REVIEW");

    /** Σ(hours×rate) over an invoice's persisted items. */
    public double invoiceItemSum(String invoiceUuid) {
        Object result = em().createNativeQuery(
                        "SELECT COALESCE(SUM(hours*rate),0) FROM invoiceitems WHERE invoiceuuid = :uuid")
                .setParameter("uuid", invoiceUuid)
                .getSingleResult();
        return ((Number) result).doubleValue();
    }

    /**
     * Σ(hours×rate) over items of LIVE credit notes pointing at {@code sourceUuid},
     * excluding {@code excludeCnUuid} (the credit note currently being finalized,
     * whose just-recalculated in-memory items are counted separately).
     */
    public double liveCreditedSum(String sourceUuid, String excludeCnUuid) {
        Object result = em().createNativeQuery(
                        "SELECT COALESCE(SUM(cii.hours*cii.rate),0) " +
                        "  FROM invoiceitems cii JOIN invoices cn ON cn.uuid = cii.invoiceuuid " +
                        " WHERE cn.creditnote_for_uuid = :src AND cn.type = 'CREDIT_NOTE' " +
                        "   AND cn.status IN (:statuses) AND cn.uuid <> :self")
                .setParameter("src", sourceUuid)
                .setParameter("statuses", LIVE_CN_STATUSES)
                .setParameter("self", excludeCnUuid == null ? "" : excludeCnUuid)
                .getSingleResult();
        return ((Number) result).doubleValue();
    }

    /**
     * Fail-closed over-credit guard, called inside the finalization transaction
     * (DRAFT → PENDING_REVIEW) for credit notes. Locks the source invoice row
     * (pessimistic write) so two concurrently finalizing credit notes serialize here:
     * the second one re-reads the live credited sum including the first and gets a
     * clean 409 instead of over-crediting the source.
     *
     * <p>No-op for anything that is not a client credit note over an {@code INVOICE}
     * source — internal credit notes stay strict 1:1 (enforced at creation) and are
     * out of scope, as are legacy credit notes without a source link.
     */
    public void assertFinalizableWithinResidual(Invoice creditNote) {
        if (creditNote.getType() != InvoiceType.CREDIT_NOTE) return;
        if (creditNote.isInternalCreditNote()) return;
        String sourceUuid = creditNote.getCreditnoteForUuid();
        if (sourceUuid == null || sourceUuid.isBlank()) return;

        // Serialize concurrent finalizations against the same source. The lock is
        // released when the surrounding finalization transaction ends.
        Invoice source = Invoice.findById(sourceUuid, LockModeType.PESSIMISTIC_WRITE);
        if (source == null) {
            log.warnf("Over-credit guard: credit note %s references missing source %s — skipping guard",
                    creditNote.getUuid(), sourceUuid);
            return;
        }
        if (source.getType() != InvoiceType.INVOICE) return;

        double sourceSum = invoiceItemSum(sourceUuid);
        double liveSum = liveCreditedSum(sourceUuid, creditNote.getUuid());
        // This credit note's items were just recalculated in memory and may not be
        // flushed yet — sum the entity state, not the DB rows.
        double thisSum = creditNote.getInvoiceitems() == null ? 0.0
                : creditNote.getInvoiceitems().stream()
                        .mapToDouble(item -> item.getHours() * item.getRate())
                        .sum();

        if (liveSum + thisSum > sourceSum + InvoiceBonusService.CREDITED_TOLERANCE_DKK) {
            throw new WebApplicationException(Response.status(Response.Status.CONFLICT)
                    .entity(String.format(
                            "Finalizing this credit note would over-credit invoice %d (credited %.2f of %.2f DKK).",
                            source.getInvoicenumber(), liveSum + thisSum, sourceSum))
                    .build());
        }
    }

    private EntityManager em() {
        return Invoice.getEntityManager();
    }
}
