package dk.trustworks.intranet.aggregates.invoice.services;

import dk.trustworks.intranet.dao.workservice.services.WorkService;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.event.Observes;
import jakarta.enterprise.event.TransactionPhase;
import jakarta.inject.Inject;
import lombok.extern.jbosslog.JBossLog;

/**
 * Marks an invoice's work items as paid-out AFTER the booking transaction commits.
 *
 * <p>The observer fires on {@link TransactionPhase#AFTER_SUCCESS}, so it runs only once the
 * booking transaction has durably committed. A failure here therefore <strong>cannot</strong>
 * roll back the booking — unlike the previous inline {@code REQUIRES_NEW} call, whose shared
 * Hibernate session let a {@code Lock wait timeout} auto-flush and revert the just-persisted
 * booked state (see {@link InvoiceBookedEvent}).
 *
 * <p>AFTER_SUCCESS observers run in the afterCompletion callback of the booking transaction,
 * with that COMPLETED transaction still associated with the thread. Any write here must
 * therefore open its own {@code REQUIRES_NEW} transaction — a plain {@code @Transactional}
 * (REQUIRED) joins the dead transaction and Narayana throws
 * {@code InactiveTransactionException} (prod incident 2026-07-01→03: every booked invoice's
 * payout write failed). {@link WorkService#registerAsPaidout} carries REQUIRES_NEW and is
 * idempotent, so a failed payout can be re-run safely.
 *
 * <p>Failures are logged, not propagated: the booking stays durable (CREATED/BOOKED) and only
 * the work-item payout needs reconciliation/retry — the safe, recoverable failure mode.
 */
@JBossLog
@ApplicationScoped
public class InvoiceBookedPayoutObserver {

    @Inject
    WorkService workService;

    void onInvoiceBooked(@Observes(during = TransactionPhase.AFTER_SUCCESS) InvoiceBookedEvent event) {
        try {
            workService.registerAsPaidout(
                    event.contractuuid(), event.projectuuid(), event.month(), event.year());
        } catch (Exception e) {
            // Exception, not RuntimeException: the tx interceptor sneaky-throws checked
            // commit-phase exceptions (RollbackException & co.) past the method signature.
            log.errorf(e, "registerAsPaidout failed AFTER booking committed for invoice %s "
                    + "(contract=%s project=%s %d-%02d) — booking is durable; work-item payout "
                    + "needs manual reconciliation/retry",
                    event.invoiceUuid(), event.contractuuid(), event.projectuuid(),
                    event.year(), event.month());
        }
    }
}
