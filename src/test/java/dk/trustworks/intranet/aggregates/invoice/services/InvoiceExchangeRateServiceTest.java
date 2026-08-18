package dk.trustworks.intranet.aggregates.invoice.services;

import dk.trustworks.intranet.aggregates.invoice.model.Invoice;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * DB-free tests for the rate-stamping decision.
 *
 * <p>The rate lookup itself talks to the currency service and is covered by the
 * caller's integration path; what matters here is that DKK invoices are left
 * alone, foreign-currency invoices are recognised, and a lookup failure never
 * blocks finalization.
 */
class InvoiceExchangeRateServiceTest {

    /** Stubs the network call so the decision logic can be exercised without HTTP. */
    private static class StubService extends InvoiceExchangeRateService {
        private final BigDecimal rate;
        boolean lookupCalled;

        StubService(BigDecimal rate) {
            this.rate = rate;
        }

        @Override
        BigDecimal lookupRate(String currency, java.time.LocalDate date) {
            lookupCalled = true;
            return rate;
        }
    }

    private static Invoice invoice(String currency) {
        Invoice inv = new Invoice();
        inv.uuid = "test-invoice";
        inv.currency = currency;
        inv.invoicedate = java.time.LocalDate.of(2025, 7, 31);
        return inv;
    }

    @Test
    void dkkInvoice_isNotLookedUpAtAll() {
        StubService svc = new StubService(new BigDecimal("7.46"));
        Invoice inv = invoice("DKK");

        svc.stampIfForeignCurrency(inv);

        assertFalse(svc.lookupCalled, "a DKK invoice must not cost a currency-service call");
        assertNull(inv.getExchangeRate(), "DKK invoices stay null so readers COALESCE to 1");
    }

    @Test
    void nullCurrency_isTreatedAsKroner() {
        StubService svc = new StubService(new BigDecimal("7.46"));
        Invoice inv = invoice(null);

        svc.stampIfForeignCurrency(inv);

        assertFalse(svc.lookupCalled);
        assertNull(inv.getExchangeRate());
    }

    @Test
    void eurInvoice_getsTheRate() {
        StubService svc = new StubService(new BigDecimal("7.46331579"));
        Invoice inv = invoice("EUR");

        svc.stampIfForeignCurrency(inv);

        assertEquals(new BigDecimal("7.46331579"), inv.getExchangeRate());
    }

    @Test
    void sekInvoice_getsTheRate() {
        // The Real Word AB, July 2025 — the rate e-conomic actually booked.
        StubService svc = new StubService(new BigDecimal("0.66960125"));
        Invoice inv = invoice("SEK");

        svc.stampIfForeignCurrency(inv);

        assertEquals(new BigDecimal("0.66960125"), inv.getExchangeRate());
    }

    @Test
    void aFailedLookup_leavesTheRateNullAndDoesNotThrow() {
        StubService svc = new StubService(null);
        Invoice inv = invoice("EUR");

        svc.stampIfForeignCurrency(inv);

        assertTrue(svc.lookupCalled);
        assertNull(inv.getExchangeRate(),
                "an unreachable currency service must not block finalizing the invoice");
    }

    @Test
    void switchingADraftBackToKroner_clearsAStaleRate() {
        StubService svc = new StubService(new BigDecimal("7.46"));
        Invoice inv = invoice("EUR");
        svc.stampIfForeignCurrency(inv);
        assertEquals(new BigDecimal("7.46"), inv.getExchangeRate());

        inv.setCurrency("DKK");
        svc.stampIfForeignCurrency(inv);

        assertNull(inv.getExchangeRate(), "a stale EUR rate on a DKK invoice would inflate it 7.5x");
    }

    @Test
    void currencyRecognition_isCaseAndWhitespaceTolerant() {
        assertTrue(InvoiceExchangeRateService.isForeignCurrency("EUR"));
        assertTrue(InvoiceExchangeRateService.isForeignCurrency("sek"));
        assertFalse(InvoiceExchangeRateService.isForeignCurrency("dkk"));
        assertFalse(InvoiceExchangeRateService.isForeignCurrency(" DKK "));
        assertFalse(InvoiceExchangeRateService.isForeignCurrency(""));
        assertFalse(InvoiceExchangeRateService.isForeignCurrency(null));
    }
}
