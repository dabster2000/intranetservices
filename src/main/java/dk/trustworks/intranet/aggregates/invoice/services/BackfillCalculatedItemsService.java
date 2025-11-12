package dk.trustworks.intranet.aggregates.invoice.services;

import dk.trustworks.intranet.aggregates.invoice.model.Invoice;
import dk.trustworks.intranet.aggregates.invoice.model.InvoiceItem;
import dk.trustworks.intranet.aggregates.invoice.model.enums.InvoiceItemOrigin;
import dk.trustworks.intranet.aggregates.invoice.model.enums.InvoiceType;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.transaction.Transactional;

import java.util.*;

/**
 * Maintenance/backfill utility to repair INTERNAL invoices that are missing
 * CALCULATED items that exist on their referenced (client) invoice.
 *
 * This is idempotent and only copies CALCULATED items when the INTERNAL invoice
 * has none, while the source has one or more.
 */
@ApplicationScoped
public class BackfillCalculatedItemsService {

    public static class BackfillSummary {
        public int invoicesScanned;
        public int invoicesRepaired;
        public List<String> repairedInvoiceUuids = new ArrayList<>();
    }

    @Transactional
    public BackfillSummary backfillAll() {
        BackfillSummary summary = new BackfillSummary();

        List<Invoice> internals = Invoice.list("type = ?1 and sourceInvoiceUuid is not null", InvoiceType.INTERNAL);
        for (Invoice internal : internals) {
            summary.invoicesScanned++;
            boolean repaired = backfillOneInternal(internal);
            if (repaired) {
                summary.invoicesRepaired++;
                summary.repairedInvoiceUuids.add(internal.getUuid());
            }
        }
        return summary;
    }

    @Transactional
    public boolean backfillOne(String internalInvoiceUuid) {
        Invoice internal = Invoice.findById(internalInvoiceUuid);
        if (internal == null || internal.getType() != InvoiceType.INTERNAL) return false;
        return backfillOneInternal(internal);
    }

    private boolean backfillOneInternal(Invoice internal) {
        if (internal.getSourceInvoiceUuid() == null || internal.getSourceInvoiceUuid().isBlank()) return false;
        Invoice source = Invoice.findById(internal.getSourceInvoiceUuid());
        if (source == null) return false;

        long srcCalc = source.getInvoiceitems().stream().filter(ii -> ii.getOrigin() == InvoiceItemOrigin.CALCULATED).count();
        long dstCalc = internal.getInvoiceitems().stream().filter(ii -> ii.getOrigin() == InvoiceItemOrigin.CALCULATED).count();
        if (srcCalc == 0 || dstCalc > 0) return false; // nothing to do or already has some

        int nextPos = internal.getInvoiceitems().stream().mapToInt(InvoiceItem::getPosition).max().orElse(0) + 1;
        for (InvoiceItem si : source.getInvoiceitems()) {
            if (si.getOrigin() != InvoiceItemOrigin.CALCULATED) continue;
            InvoiceItem ni = new InvoiceItem(
                    si.getConsultantuuid(),
                    si.getItemname(),
                    si.getDescription(),
                    si.getRate(),
                    si.getHours(),
                    nextPos++,
                    internal.getUuid(),
                    si.getOrigin()
            );
            ni.setCalculationRef(si.getCalculationRef());
            ni.setRuleId(si.getRuleId());
            ni.setLabel(si.getLabel());
            internal.getInvoiceitems().add(ni);
            ni.persist();
        }
        internal.persist();
        return true;
    }
}
