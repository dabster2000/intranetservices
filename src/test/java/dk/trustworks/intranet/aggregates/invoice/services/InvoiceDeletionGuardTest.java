package dk.trustworks.intranet.aggregates.invoice.services;

import dk.trustworks.intranet.aggregates.invoice.model.Invoice;
import dk.trustworks.intranet.aggregates.invoice.model.enums.EconomicsInvoiceStatus;
import dk.trustworks.intranet.aggregates.invoice.model.enums.InvoiceType;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Unit tests for the e-conomic booking guard used by
 * {@link InvoiceService#deleteDraftInvoice(String)}: an invoice already booked in
 * e-conomic must never be deleted locally, because the e-conomic document lives on and
 * deleting the local row orphans it and tears a gap in the booked-number sequence
 * (incident: e-conomic 27886 deleted locally 2026-05-01, re-booked as 27897 → duplicate).
 *
 * <p>Plain JUnit — no DB or Quarkus container needed.
 */
class InvoiceDeletionGuardTest {

    private static Invoice invoice(InvoiceType type, Integer bookedNumber, int voucher,
                                   EconomicsInvoiceStatus status) {
        Invoice inv = new Invoice();
        inv.setType(type);
        inv.setEconomicsBookedNumber(bookedNumber);
        inv.setEconomicsVoucherNumber(voucher);
        inv.setEconomicsStatus(status);
        return inv;
    }

    @Test
    void plain_draft_is_deletable() {
        // No booked number, no voucher, status NA — a normal draft (incl. one that only
        // has an e-conomic DRAFT, which cancelFinalization removes first).
        assertFalse(InvoiceService.isBookedInEconomics(
                invoice(InvoiceType.INVOICE, null, 0, EconomicsInvoiceStatus.NA)));
    }

    @Test
    void booked_number_blocks_delete() {
        assertTrue(InvoiceService.isBookedInEconomics(
                invoice(InvoiceType.INVOICE, 27886, 0, EconomicsInvoiceStatus.BOOKED)));
    }

    @Test
    void posted_debtor_voucher_blocks_delete() {
        assertTrue(InvoiceService.isBookedInEconomics(
                invoice(InvoiceType.INTERNAL, null, 6037219, EconomicsInvoiceStatus.PARTIALLY_UPLOADED)));
    }

    @Test
    void booked_or_paid_status_blocks_delete_even_without_number() {
        assertTrue(InvoiceService.isBookedInEconomics(
                invoice(InvoiceType.INVOICE, null, 0, EconomicsInvoiceStatus.BOOKED)));
        assertTrue(InvoiceService.isBookedInEconomics(
                invoice(InvoiceType.INVOICE, null, 0, EconomicsInvoiceStatus.PAID)));
    }

    @Test
    void phantom_mirror_is_always_deletable() {
        // 171/189 PHANTOM rows carry a voucher number, but they are local mirrors of
        // e-conomic entries created by the import — deleting one orphans nothing, so the
        // normal isDraftOrPhantom path must keep handling them.
        assertFalse(InvoiceService.isBookedInEconomics(
                invoice(InvoiceType.PHANTOM, null, 6037219, EconomicsInvoiceStatus.NA)));
        assertFalse(InvoiceService.isBookedInEconomics(
                invoice(InvoiceType.PHANTOM, 12345, 0, EconomicsInvoiceStatus.BOOKED)));
    }
}
