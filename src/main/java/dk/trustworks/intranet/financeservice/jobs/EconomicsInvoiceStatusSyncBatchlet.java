package dk.trustworks.intranet.financeservice.jobs;

import dk.trustworks.intranet.aggregates.invoice.model.Invoice;
import dk.trustworks.intranet.aggregates.invoice.model.enums.FinanceStatus;
import dk.trustworks.intranet.batch.monitoring.BatchExceptionTracking;
import dk.trustworks.intranet.expenseservice.services.EconomicsInvoiceStatusService;
import jakarta.batch.api.AbstractBatchlet;
import jakarta.enterprise.context.Dependent;
import jakarta.inject.Inject;
import jakarta.inject.Named;
import jakarta.transaction.Transactional;
import lombok.extern.jbosslog.JBossLog;

import java.util.List;

@JBossLog
@Named("economicsInvoiceStatusSyncBatchlet")
@Dependent
@BatchExceptionTracking
public class EconomicsInvoiceStatusSyncBatchlet extends AbstractBatchlet {

    @Inject
    EconomicsInvoiceStatusService statusService;

    @Override
    @Transactional
    public String process() throws Exception {
        log.info("EconomicsInvoiceStatusSyncBatchlet started");
        // Find invoices that have been numbered and are not yet PAID in e-conomics
        List<Invoice> candidates = Invoice.list("invoicenumber > 0 and financeStatus in ?1",
                List.of(FinanceStatus.UPLOADED, FinanceStatus.BOOKED));

        log.infof("EconomicsInvoiceStatusSync: scanning %d invoices", candidates.size());
        int updated = 0;

        for (Invoice inv : candidates) {
            try {
                FinanceStatus current = inv.getFinanceStatus();
                FinanceStatus newStatus = current;
                log.debugf("Processing invoice %s currentFinanceStatus=%s", inv.getUuid(), current);

                // If NONE or UPLOADED, check if booked
                if (current == FinanceStatus.NONE || current == FinanceStatus.UPLOADED) {
                    boolean booked = statusService.isBooked(inv);
                    log.debugf("Booked check for invoice %s -> %s", inv.getUuid(), booked);
                    if (booked) {
                        newStatus = FinanceStatus.BOOKED;
                    }
                }
                // If booked, check paid
                if (newStatus == FinanceStatus.BOOKED) {
                    boolean paid = statusService.isPaid(inv);
                    log.debugf("Paid check for invoice %s -> %s", inv.getUuid(), paid);
                    if (paid) {
                        newStatus = FinanceStatus.PAID;
                    }
                }

                if (newStatus != current) {
                    inv.setFinanceStatus(newStatus);
                    inv.persist();
                    updated++;
                    log.infof("Invoice %s finance status moved from %s to %s", inv.getUuid(), current, newStatus);
                } else {
                    log.debugf("No finance status change for invoice %s (still %s)", inv.getUuid(), current);
                }
            } catch (Exception e) {
                log.warnf(e, "Failed status sync for invoice %s", inv.getUuid());
            }
        }
        log.infof("EconomicsInvoiceStatusSync completed; processed=%d, updated=%d", candidates.size(), updated);
        return "COMPLETED";
    }
}
