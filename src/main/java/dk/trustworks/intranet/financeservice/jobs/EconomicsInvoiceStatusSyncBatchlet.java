package dk.trustworks.intranet.financeservice.jobs;

import dk.trustworks.intranet.aggregates.invoice.model.Invoice;
import dk.trustworks.intranet.aggregates.invoice.model.enums.EconomicsInvoiceStatus;
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
public class EconomicsInvoiceStatusSyncBatchlet extends AbstractBatchlet {

    @Inject
    EconomicsInvoiceStatusService statusService;

    @Override
    @Transactional
    public String process() throws Exception {
        log.info("EconomicsInvoiceStatusSyncBatchlet started");
        // Find invoices that have been numbered and are not yet PAID in e-conomics
        List<Invoice> candidates = Invoice.list("invoicenumber > 0 and economicsStatus in ?1",
                List.of(EconomicsInvoiceStatus.UPLOADED, EconomicsInvoiceStatus.BOOKED));

        log.infof("EconomicsInvoiceStatusSync: scanning %d invoices", candidates.size());
        int updated = 0;

        for (Invoice inv : candidates) {
            try {
                EconomicsInvoiceStatus current = inv.getEconomicsStatus();
                EconomicsInvoiceStatus newStatus = current;
                log.debugf("Processing invoice %s currentStatus=%s", inv.getUuid(), current);

                // If NA or UPLOADED, check if booked
                if (current == EconomicsInvoiceStatus.NA || current == EconomicsInvoiceStatus.UPLOADED) {
                    boolean booked = statusService.isBooked(inv);
                    log.debugf("Booked check for invoice %s -> %s", inv.getUuid(), booked);
                    if (booked) {
                        newStatus = EconomicsInvoiceStatus.BOOKED;
                    }
                }
                // If booked, check paid
                if (newStatus == EconomicsInvoiceStatus.BOOKED) {
                    boolean paid = statusService.isPaid(inv);
                    log.debugf("Paid check for invoice %s -> %s", inv.getUuid(), paid);
                    if (paid) {
                        newStatus = EconomicsInvoiceStatus.PAID;
                    }
                }

                if (newStatus != current) {
                    inv.setEconomicsStatus(newStatus);
                    inv.persist();
                    updated++;
                    log.infof("Invoice %s moved from %s to %s", inv.getUuid(), current, newStatus);
                } else {
                    log.debugf("No status change for invoice %s (still %s)", inv.getUuid(), current);
                }
            } catch (Exception e) {
                log.warnf(e, "Failed status sync for invoice %s", inv.getUuid());
            }
        }
        log.infof("EconomicsInvoiceStatusSync completed; processed=%d, updated=%d", candidates.size(), updated);
        return "COMPLETED";
    }
}
