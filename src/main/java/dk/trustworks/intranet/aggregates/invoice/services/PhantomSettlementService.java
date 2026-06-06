package dk.trustworks.intranet.aggregates.invoice.services;

import dk.trustworks.intranet.aggregates.invoice.dto.SettlementGroupKey;
import dk.trustworks.intranet.aggregates.invoice.model.InternalInvoicePhantomLink;
import dk.trustworks.intranet.aggregates.invoice.model.Invoice;
import dk.trustworks.intranet.aggregates.invoice.model.enums.InvoiceType;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.persistence.EntityManager;
import jakarta.transaction.Transactional;
import org.jboss.logging.Logger;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Consolidated, delta-based settlement of cross-company PHANTOM labour.
 * Built up across phases: Phase 2 — group-key derivation only (inert);
 * Phase 3 — backfill; Phase 4 — listSettlementGroups + previewGroup (read);
 * Phase 5 — settleGroup (write). See the master plan's Shared contracts.
 */
@ApplicationScoped
public class PhantomSettlementService {

    private static final Logger log = Logger.getLogger(PhantomSettlementService.class);

    /**
     * Settlement-group key for a phantom: (billing client, receiving/debtor company,
     * year, month). Returns null when the phantom is unmapped (no billing client) —
     * such phantoms cannot form a group and are handled by the review queue.
     */
    public SettlementGroupKey groupKeyOf(Invoice phantom) {
        if (phantom == null) return null;
        String companyUuid = (phantom.getCompany() != null) ? phantom.getCompany().getUuid() : null;
        return SettlementGroupKey.from(phantom.getBillingClientUuid(), companyUuid,
                phantom.getYear(), phantom.getMonth());
    }

    @Inject
    EntityManager em;

    /**
     * Self-injected proxy so the per-internal REQUIRES_NEW boundary actually engages
     * (a plain this.method() self-call bypasses the Arc interceptor). One failed
     * internal cannot roll back the whole backfill. Mirrors PhantomAttributionService.self.
     */
    @Inject
    PhantomSettlementService self;

    /** Outcome of backfilling one internal — keys of the returned counts map. */
    public enum BackfillOutcome { STAMPED, ALREADY_DONE, SKIPPED_UNMAPPED, SKIPPED_NO_PHANTOM, SKIPPED_NO_INTERNAL, ERROR }

    /**
     * One-time, idempotent backfill (Decision D5). Stamps the settlement-group key and
     * writes an internal_invoice_phantom_link row onto every already-issued
     * INTERNAL/INTERNAL_SERVICE invoice whose invoice_ref_uuid points to an in-scope phantom.
     * Human-invoked (POST .../settlement/backfill); never auto-runs. NOT @Transactional:
     * each item commits in its own REQUIRES_NEW tx via self, so partial progress is durable
     * and there is no outer snapshot (the REPEATABLE-READ trap is structurally absent).
     */
    public Map<String, Integer> backfillExistingInternals() {
        List<String> internalUuids = self.listBackfillCandidateInternalUuids();
        Map<String, Integer> counts = new LinkedHashMap<>();
        for (String uuid : internalUuids) {
            BackfillOutcome outcome;
            try {
                outcome = self.backfillOneInternal(uuid);
            } catch (RuntimeException e) {
                log.errorf(e, "backfillOneInternal failed for internal=%s", uuid);
                outcome = BackfillOutcome.ERROR;
            }
            counts.merge(outcome.name(), 1, Integer::sum);
        }
        log.infof("backfillExistingInternals: processed=%d result=%s", internalUuids.size(), counts);
        return counts;
    }

    /** Candidate internals: INTERNAL/INTERNAL_SERVICE whose ref is an in-scope phantom. */
    @Transactional(Transactional.TxType.REQUIRES_NEW)
    public List<String> listBackfillCandidateInternalUuids() {
        String sql = """
            SELECT i.uuid
            FROM invoices i
            JOIN invoices p ON p.uuid = i.invoice_ref_uuid
            WHERE i.type IN ('INTERNAL','INTERNAL_SERVICE')
              AND i.invoice_ref_uuid IS NOT NULL
              AND p.type = 'PHANTOM'
              AND p.economics_entry_number IS NOT NULL
            ORDER BY i.uuid
        """;
        @SuppressWarnings("unchecked")
        List<String> ids = em.createNativeQuery(sql).getResultList();
        return ids;
    }

    /**
     * Stamp one internal + write its phantom-link row. Idempotent: re-running is
     * ALREADY_DONE once both the stamp and the link exist. attributed_amount_at_issue
     * is the internal's own signed total (its lines came from that phantom).
     */
    @Transactional(Transactional.TxType.REQUIRES_NEW)
    public BackfillOutcome backfillOneInternal(String internalUuid) {
        Invoice internal = Invoice.findById(internalUuid);
        if (internal == null) return BackfillOutcome.SKIPPED_NO_INTERNAL;

        String refUuid = internal.getInvoiceRefUuid();
        if (refUuid == null || refUuid.isBlank()) return BackfillOutcome.SKIPPED_NO_PHANTOM;
        Invoice phantom = Invoice.findById(refUuid);
        if (phantom == null || phantom.getType() != InvoiceType.PHANTOM) return BackfillOutcome.SKIPPED_NO_PHANTOM;

        SettlementGroupKey key = groupKeyOf(phantom);
        if (key == null) return BackfillOutcome.SKIPPED_UNMAPPED;

        boolean alreadyStamped = internal.getSettlementYear() != null;
        boolean linkExists = InternalInvoicePhantomLink
                .count("internalUuid = ?1 and phantomUuid = ?2", internalUuid, phantom.getUuid()) > 0;
        if (alreadyStamped && linkExists) return BackfillOutcome.ALREADY_DONE;

        if (!alreadyStamped) {
            internal.setSettlementBillingClientUuid(key.billingClientUuid());
            internal.setSettlementDebtorCompanyuuid(key.debtorCompanyUuid());
            internal.setSettlementYear(key.year());
            internal.setSettlementMonth(key.month());
        }
        if (!linkExists) {
            new InternalInvoicePhantomLink(internalUuid, phantom.getUuid(), internalTotalSigned(internalUuid)).persist();
        }
        return BackfillOutcome.STAMPED;
    }

    /** Signed Σ(hours*rate) over an invoice's items (negative for a credit note). */
    BigDecimal internalTotalSigned(String invoiceUuid) {
        Object result = em.createNativeQuery(
                "SELECT COALESCE(SUM(hours*rate),0) FROM invoiceitems WHERE invoiceuuid = :id")
                .setParameter("id", invoiceUuid)
                .getSingleResult();
        BigDecimal v = (result == null) ? BigDecimal.ZERO
                : (result instanceof BigDecimal b ? b : BigDecimal.valueOf(((Number) result).doubleValue()));
        return v.setScale(2, RoundingMode.HALF_UP);
    }
}
