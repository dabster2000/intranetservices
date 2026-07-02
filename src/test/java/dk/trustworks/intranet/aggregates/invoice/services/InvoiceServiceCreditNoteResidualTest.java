package dk.trustworks.intranet.aggregates.invoice.services;

import dk.trustworks.intranet.aggregates.invoice.model.Invoice;
import dk.trustworks.intranet.aggregates.invoice.model.InvoiceItem;
import dk.trustworks.intranet.aggregates.invoice.model.enums.InvoiceItemOrigin;
import dk.trustworks.intranet.aggregates.invoice.model.enums.InvoiceStatus;
import dk.trustworks.intranet.aggregates.invoice.model.enums.InvoiceType;
import jakarta.ws.rs.WebApplicationException;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Pure unit tests for the multi-credit-note residual pre-fill
 * ({@link InvoiceService#prefillResidualItems}) — no DB, no Quarkus. Covers the
 * per-item residual math via {@code sourceItemUuid}, the legacy invoice-level
 * fallback, the omission of fully credited (never 0-hour) lines, negative-residual
 * discount lines, CALCULATED metadata carry-over, and the open-draft 409 guard.
 * Spec: 2026-07-02-multiple-credit-notes-per-invoice-design.md §5.2.
 */
class InvoiceServiceCreditNoteResidualTest {

    private final InvoiceService service = new InvoiceService();

    private static Invoice invoice(InvoiceType type, InvoiceStatus status) {
        Invoice inv = new Invoice();
        inv.uuid = UUID.randomUUID().toString();
        inv.type = type;
        inv.status = status;
        inv.invoicenumber = 12345;
        return inv;
    }

    private static InvoiceItem item(Invoice owner, String consultant, double rate, double hours) {
        InvoiceItem it = new InvoiceItem(consultant, "Consulting", "Work", rate, hours, 1, owner.uuid, InvoiceItemOrigin.BASE);
        owner.invoiceitems.add(it);
        return it;
    }

    /** A CN line crediting {@code sourceItem} for {@code hours} at the item's rate. */
    private static InvoiceItem cnItem(Invoice cn, InvoiceItem sourceItem, double hours) {
        InvoiceItem it = new InvoiceItem(sourceItem.consultantuuid, sourceItem.getItemname(),
                sourceItem.getDescription(), sourceItem.getRate(), hours, 1, cn.uuid, sourceItem.getOrigin());
        it.sourceItemUuid = sourceItem.uuid;
        cn.invoiceitems.add(it);
        return it;
    }

    @Test
    void perItemResidual_omitsFullyCreditedLines_andEmitsResidualAtOriginalRate() {
        Invoice source = invoice(InvoiceType.INVOICE, InvoiceStatus.CREATED);
        InvoiceItem a = item(source, "user-a", 1000.0, 10.0); // 10.000
        InvoiceItem b = item(source, "user-b", 800.0, 5.0);   //  4.000

        Invoice cn1 = invoice(InvoiceType.CREDIT_NOTE, InvoiceStatus.CREATED);
        cnItem(cn1, a, 10.0); // A fully credited
        cnItem(cn1, b, 2.0);  // B partially credited (1.600 of 4.000)

        Invoice newCn = invoice(InvoiceType.CREDIT_NOTE, InvoiceStatus.DRAFT);
        service.prefillResidualItems(source, List.of(cn1), newCn);

        assertEquals(1, newCn.invoiceitems.size(), "fully credited line A must be omitted");
        InvoiceItem residual = newCn.invoiceitems.get(0);
        assertEquals(b.uuid, residual.sourceItemUuid);
        assertEquals(800.0, residual.getRate(), 1e-9, "residual keeps the ORIGINAL rate");
        assertEquals(3.0, residual.getHours(), 1e-9, "hours = residual / rate");
        assertEquals("user-b", residual.consultantuuid);
    }

    @Test
    void draftCreditNotes_deductFromResidual_soTwoDraftsCannotClaimTheSameAmount() {
        Invoice source = invoice(InvoiceType.INVOICE, InvoiceStatus.CREATED);
        InvoiceItem a = item(source, "user-a", 1000.0, 10.0);

        Invoice draftCn = invoice(InvoiceType.CREDIT_NOTE, InvoiceStatus.DRAFT);
        cnItem(draftCn, a, 6.0); // an OPEN DRAFT claiming 6.000

        Invoice newCn = invoice(InvoiceType.CREDIT_NOTE, InvoiceStatus.DRAFT);
        service.prefillResidualItems(source, List.of(draftCn), newCn);

        assertEquals(1, newCn.invoiceitems.size());
        assertEquals(4.0, newCn.invoiceitems.get(0).getHours(), 1e-9,
                "draft CN's claim is deducted from the pre-fill");
    }

    @Test
    void legacyItemsWithoutSourceLink_fallBackToSingleInvoiceLevelResidualLine() {
        Invoice source = invoice(InvoiceType.INVOICE, InvoiceStatus.CREATED);
        item(source, "user-a", 1000.0, 10.0); // 10.000

        Invoice legacyCn = invoice(InvoiceType.CREDIT_NOTE, InvoiceStatus.CREATED);
        InvoiceItem unlinked = new InvoiceItem(null, "Manuel kreditering", "", 4000.0, 1.0, 1,
                legacyCn.uuid, InvoiceItemOrigin.BASE); // no sourceItemUuid
        legacyCn.invoiceitems.add(unlinked);

        Invoice newCn = invoice(InvoiceType.CREDIT_NOTE, InvoiceStatus.DRAFT);
        service.prefillResidualItems(source, List.of(legacyCn), newCn);

        assertEquals(1, newCn.invoiceitems.size());
        InvoiceItem line = newCn.invoiceitems.get(0);
        assertNull(line.sourceItemUuid);
        assertEquals(1.0, line.getHours(), 1e-9);
        assertEquals(6000.0, line.getRate(), 1e-9, "rate carries the invoice-level residual");
        assertTrue(line.getDescription().contains("Restkreditering"));
    }

    @Test
    void residualFullyClaimedByOpenDraft_throws409InsteadOfCreatingEmptyCreditNote() {
        Invoice source = invoice(InvoiceType.INVOICE, InvoiceStatus.CREATED);
        InvoiceItem a = item(source, "user-a", 1000.0, 10.0);

        Invoice draftCn = invoice(InvoiceType.CREDIT_NOTE, InvoiceStatus.DRAFT);
        cnItem(draftCn, a, 10.0); // draft claims everything

        Invoice newCn = invoice(InvoiceType.CREDIT_NOTE, InvoiceStatus.DRAFT);
        WebApplicationException ex = assertThrows(WebApplicationException.class,
                () -> service.prefillResidualItems(source, List.of(draftCn), newCn));
        assertEquals(409, ex.getResponse().getStatus());
    }

    @Test
    void calculatedItems_keepCalculationMetadataOnTheResidualLine() {
        Invoice source = invoice(InvoiceType.INVOICE, InvoiceStatus.CREATED);
        InvoiceItem fee = item(source, null, -400.0, 1.0); // pricing-engine discount line
        fee.setOrigin(InvoiceItemOrigin.CALCULATED);
        fee.setCalculationRef("calc-ref");
        fee.setRuleId("rule-1");
        fee.setLabel("4% administrationsgebyr");
        InvoiceItem work = item(source, "user-a", 1000.0, 10.0);

        Invoice cn1 = invoice(InvoiceType.CREDIT_NOTE, InvoiceStatus.CREATED);
        cnItem(cn1, work, 4.0); // partial on the work line, nothing on the fee line

        Invoice newCn = invoice(InvoiceType.CREDIT_NOTE, InvoiceStatus.DRAFT);
        service.prefillResidualItems(source, List.of(cn1), newCn);

        InvoiceItem feeResidual = newCn.invoiceitems.stream()
                .filter(i -> fee.uuid.equals(i.sourceItemUuid)).findFirst().orElseThrow();
        assertEquals(InvoiceItemOrigin.CALCULATED, feeResidual.getOrigin());
        assertEquals("calc-ref", feeResidual.getCalculationRef());
        assertEquals("rule-1", feeResidual.getRuleId());
        assertEquals("4% administrationsgebyr", feeResidual.getLabel());
        assertEquals(-400.0, feeResidual.getRate(), 1e-9);
        assertEquals(1.0, feeResidual.getHours(), 1e-9, "uncredited negative line carries over in full");
    }

    @Test
    void noLineEverHasZeroHours() {
        Invoice source = invoice(InvoiceType.INVOICE, InvoiceStatus.CREATED);
        InvoiceItem a = item(source, "user-a", 1000.0, 10.0);
        InvoiceItem b = item(source, "user-b", 500.0, 4.0);

        Invoice cn1 = invoice(InvoiceType.CREDIT_NOTE, InvoiceStatus.CREATED);
        cnItem(cn1, a, 10.0);
        cnItem(cn1, b, 1.0);

        Invoice newCn = invoice(InvoiceType.CREDIT_NOTE, InvoiceStatus.DRAFT);
        service.prefillResidualItems(source, List.of(cn1), newCn);

        assertFalse(newCn.invoiceitems.isEmpty());
        assertTrue(newCn.invoiceitems.stream().allMatch(i -> i.getHours() != 0.0),
                "0-hour lines void the whole Q2C bulk booking (prod incident 2026-07-01)");
    }
}
