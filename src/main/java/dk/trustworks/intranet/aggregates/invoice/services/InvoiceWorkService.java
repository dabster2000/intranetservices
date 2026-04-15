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
     */
    @Transactional
    public void registerAsPaidout(Invoice invoice) {
        delegate.registerAsPaidout(
                invoice.getContractuuid(),
                invoice.getProjectuuid(),
                invoice.getMonth(),
                invoice.getYear());
    }
}
