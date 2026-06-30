package dk.trustworks.intranet.aggregates.invoice.services;

import dk.trustworks.intranet.aggregates.invoice.model.Invoice;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.transaction.Transactional;

/**
 * Facade over {@link dk.trustworks.intranet.dao.workservice.services.WorkService}
 * that provides an Invoice-level API for the orchestrator.
 *
 * The underlying WorkService.registerAsPaidout takes
 * (contractuuid, projectuuid, month, year); this facade extracts those from the
 * Invoice aggregate so the orchestrator does not need to know the work subsystem
 * internals.
 */
@ApplicationScoped
public class InvoiceWorkService {

    @Inject
    dk.trustworks.intranet.dao.workservice.services.WorkService delegate;

    /**
     * Marks all work items on the invoice's contract/project/month/year as paid out.
     * This is an irreversible side effect — call only after the invoice is booked.
     *
     * <p>Runs in its OWN transaction (REQUIRES_NEW). The caller ({@code bookDraft}) has
     * already booked the invoice in e-conomic and persisted the booked number in its
     * transaction. The {@code update work set paid_out} below can hit a row-lock
     * contention ("Lock wait timeout exceeded") under load; if that exception were to
     * propagate inside the caller's transaction it would mark it rollback-only and undo
     * the booked-number persist, leaving e-conomic booked but the local row reverted
     * and deletable (incident: e-conomic 27886 orphaned, 2026-05-01). Isolating this in
     * a separate transaction means a failure here rolls back only the payout update —
     * the caller catches it, logs it, and the booking stays durable for manual
     * work-item reconciliation. Reads only scalar invoice fields, so the detached entity
     * passed across the transaction boundary is safe (no lazy initialization).
     */
    @Transactional(Transactional.TxType.REQUIRES_NEW)
    public void registerAsPaidout(Invoice invoice) {
        delegate.registerAsPaidout(
                invoice.getContractuuid(),
                invoice.getProjectuuid(),
                invoice.getMonth(),
                invoice.getYear());
    }
}
