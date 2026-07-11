package dk.trustworks.intranet.aggregates.invoice.resources;

import dk.trustworks.intranet.aggregates.invoice.model.Invoice;
import dk.trustworks.intranet.aggregates.invoice.model.InvoiceItem;
import dk.trustworks.intranet.aggregates.invoice.model.enums.InvoiceStatus;
import dk.trustworks.intranet.aggregates.invoice.model.enums.InvoiceType;
import dk.trustworks.intranet.aggregates.invoice.pricing.PricingEngine;
import dk.trustworks.intranet.contracts.services.PricingPreviewService;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;

/** Regression guard: the three frozen/bypass paths must never invoke explain mode. */
class PricingResourceBypassTest {

    @Test
    void creditNote_internal_andFinalizedBypasses_doNotInvokeExplainMode() {
        PricingResource resource = new PricingResource();
        resource.pricingEngine = mock(PricingEngine.class);
        resource.pricingPreviewService = mock(PricingPreviewService.class);

        Invoice creditNote = invoice(InvoiceType.CREDIT_NOTE, InvoiceStatus.DRAFT);
        resource.preview(creditNote);

        Invoice internal = invoice(InvoiceType.INTERNAL, InvoiceStatus.DRAFT);
        internal.getInvoiceitems().add(item(100.0));
        resource.preview(internal);

        Invoice finalized = invoice(InvoiceType.INVOICE, InvoiceStatus.CREATED);
        finalized.getInvoiceitems().add(item(100.0));
        resource.preview(finalized);

        assertNull(creditNote.getPricingPreview());
        assertNull(internal.getPricingPreview());
        assertNull(finalized.getPricingPreview());
        verifyNoInteractions(resource.pricingEngine, resource.pricingPreviewService);
    }

    private static Invoice invoice(InvoiceType type, InvoiceStatus status) {
        Invoice invoice = new Invoice();
        invoice.setType(type);
        invoice.setStatus(status);
        return invoice;
    }

    private static InvoiceItem item(double rate) {
        InvoiceItem item = new InvoiceItem();
        item.setHours(1.0);
        item.setRate(rate);
        return item;
    }
}
