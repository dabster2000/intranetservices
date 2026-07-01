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
 * booking transaction has durably committed, and {@link WorkService#registerAsPaidout} runs
 * in its OWN (new) transaction. A failure here therefore <strong>cannot</strong> roll back the
 * booking — unlike the previous inline {@code REQUIRES_NEW} call, whose shared Hibernate
 * session let a {@code Lock wait timeout} auto-flush and revert the just-persisted booked
 * state (see {@link InvoiceBookedEvent}).
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
        } catch (RuntimeException e) {
            log.errorf(e, "registerAsPaidout failed AFTER booking committed for invoice %s "
                    + "(contract=%s project=%s %d-%02d) — booking is durable; work-item payout "
                    + "needs manual reconciliation/retry",
                    event.invoiceUuid(), event.contractuuid(), event.projectuuid(),
                    event.year(), event.month());
        }
    }
}
