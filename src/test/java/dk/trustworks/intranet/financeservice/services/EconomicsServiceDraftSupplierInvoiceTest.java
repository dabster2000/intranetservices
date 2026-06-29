package dk.trustworks.intranet.financeservice.services;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * Unit coverage for the pure transformation helpers behind the intercompany draft
 * supplier-invoice sync ({@link EconomicsService#loadDraftSupplierInvoices}). These are the
 * financial-correctness-critical pieces; both were verified to the øre against live e-conomic
 * data + the {@code invoices} table (A/S "Kreditor Intern" daybook, FY25/26).
 */
class EconomicsServiceDraftSupplierInvoiceTest {

    private static final double EPS = 0.005;

    @Test
    void netCost_negatesCreditorSignAndStripsVat_matchingBookedGl() {
        // Invoice 70368: gross −165,687.50 (creditor convention), 25% VAT → +132,550.00 cost.
        assertEquals(132550.00, EconomicsService.netCostFromDraftGross(-165687.50, 25.0), EPS);
        // Credit note 70363: gross +334,753.58, 25% VAT → −267,802.86 (reduces cost).
        assertEquals(-267802.86, EconomicsService.netCostFromDraftGross(334753.58, 25.0), EPS);
    }

    @Test
    void netCost_handlesNoVat() {
        assertEquals(100000.00, EconomicsService.netCostFromDraftGross(-100000.00, 0.0), EPS);
        assertEquals(-50000.00, EconomicsService.netCostFromDraftGross(50000.00, 0.0), EPS);
    }

    @Test
    void parseSupplierInvoiceNumber_stripsDashAndNonDigits() {
        assertEquals(70368, EconomicsService.parseSupplierInvoiceNumber("70-368"));
        assertEquals(50089, EconomicsService.parseSupplierInvoiceNumber("50-089"));
        assertEquals(0, EconomicsService.parseSupplierInvoiceNumber(null));
        assertEquals(0, EconomicsService.parseSupplierInvoiceNumber(""));
        assertEquals(0, EconomicsService.parseSupplierInvoiceNumber("abc"));
    }
}
