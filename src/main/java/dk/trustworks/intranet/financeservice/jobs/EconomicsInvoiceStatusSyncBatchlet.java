package dk.trustworks.intranet.financeservice.jobs;

import dk.trustworks.intranet.aggregates.invoice.model.Invoice;
import dk.trustworks.intranet.aggregates.invoice.model.enums.EconomicsInvoiceStatus;
import dk.trustworks.intranet.batch.monitoring.BatchExceptionTracking;
import dk.trustworks.intranet.expenseservice.services.EconomicsInvoiceStatusService;
import io.quarkus.narayana.jta.QuarkusTransaction;
import jakarta.batch.api.AbstractBatchlet;
import jakarta.enterprise.context.Dependent;
import jakarta.inject.Inject;
import jakarta.inject.Named;
import lombok.extern.jbosslog.JBossLog;
import org.eclipse.microprofile.config.inject.ConfigProperty;

import java.time.LocalDate;
import java.util.List;

@JBossLog
@Named("economicsInvoiceStatusSyncBatchlet")
@Dependent
@BatchExceptionTracking
public class EconomicsInvoiceStatusSyncBatchlet extends AbstractBatchlet {

    @Inject
    EconomicsInvoiceStatusService statusService;

    /**
     * Only invoices issued within this many days are polled for BOOKED -> PAID. Bounds an
     * otherwise ever-growing scan (every BOOKED-but-unpaid invoice, forever). Deliberately
     * generous: real payments land far inside a year, but widen it if late payments are expected.
     */
    @ConfigProperty(name = "dk.trustworks.invoice.economics-sync.recency-days", defaultValue = "365")
    int recencyDays;

    /** Recency cutoff date; {@code today} is injected so the window is unit-testable. */
    public static LocalDate computeCutoff(LocalDate today, int recencyDays) {
        return today.minusDays(recencyDays);
    }

    /**
     * Candidate invoices to poll: numbered, still BOOKED, and issued on/after {@code cutoff}.
     * Returns uuids only (not entities) so we don't hydrate each invoice's EAGER invoiceitems
     * just to enumerate the work. Must be called inside an active transaction/session.
     */
    public static List<String> selectCandidateUuids(LocalDate cutoff) {
        return Invoice.getEntityManager().createQuery(
                        "select i.uuid from Invoice i " +
                                "where i.invoicenumber > 0 and i.economicsStatus = :status " +
                                "and i.invoicedate >= :cutoff order by i.invoicedate", String.class)
                .setParameter("status", EconomicsInvoiceStatus.BOOKED)
                .setParameter("cutoff", cutoff)
                .getResultList();
    }

    @Override
    public String process() throws Exception {
        log.info("EconomicsInvoiceStatusSyncBatchlet started");
        LocalDate cutoff = computeCutoff(LocalDate.now(), recencyDays);

        // Phase 1 — collect candidate UUIDs in a short read transaction (no HTTP held).
        List<String> uuids = QuarkusTransaction.requiringNew().call(() -> selectCandidateUuids(cutoff));

        log.infof("EconomicsInvoiceStatusSync: scanning %d invoices (BOOKED, issued since %s)", uuids.size(), cutoff);

        // Visibility for the recency trade-off: how many BOOKED invoices fall outside the window
        // and are therefore NOT polled. If this grows, a real late payment may be going undetected.
        long aged = QuarkusTransaction.requiringNew().call(() ->
                Invoice.count("invoicenumber > 0 and economicsStatus = ?1 and invoicedate < ?2",
                        EconomicsInvoiceStatus.BOOKED, cutoff));
        if (aged > 0) {
            log.infof("EconomicsInvoiceStatusSync: %d BOOKED invoices older than %s are NOT polled (recency cutoff); " +
                    "widen dk.trustworks.invoice.economics-sync.recency-days if late payments are expected", aged, cutoff);
        }

        int updated = 0;
        for (String uuid : uuids) {
            try {
                // Phase 2 — one short transaction per invoice. Re-load inside the tx so isPaid() has a
                // live session for invoice.getCompany()/agreement-token reads. The e-conomic HTTP call
                // is held only for THIS invoice (~1s) instead of across the whole scan — the previous
                // single @Transactional held one DB connection open for every HTTP call in the run.
                Boolean paid = QuarkusTransaction.requiringNew().call(() -> {
                    Invoice inv = Invoice.findById(uuid);
                    if (inv == null) return Boolean.FALSE;
                    boolean isPaid = statusService.isPaid(inv);
                    if (isPaid) {
                        inv.setEconomicsStatus(EconomicsInvoiceStatus.PAID); // managed → flushed on commit
                    }
                    return isPaid;
                });
                if (Boolean.TRUE.equals(paid)) {
                    updated++;
                    log.infof("Invoice %s moved from BOOKED to PAID", uuid);
                }
            } catch (Exception e) {
                log.warnf(e, "Failed status sync for invoice %s", uuid);
            }
        }
        log.infof("EconomicsInvoiceStatusSync completed; processed=%d, updated=%d", uuids.size(), updated);
        return "COMPLETED";
    }
}
