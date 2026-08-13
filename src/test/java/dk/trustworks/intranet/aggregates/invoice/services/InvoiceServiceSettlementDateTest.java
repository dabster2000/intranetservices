package dk.trustworks.intranet.aggregates.invoice.services;

import org.junit.jupiter.api.Test;

import java.time.LocalDate;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * Tests for {@link InvoiceService#settlementInvoiceDate(LocalDate)} — the settlement dating
 * convention for INTERNAL_SERVICE drafts created from the distribution page.
 *
 * <p>Convention: the invoice is dated at the end (June 30) of the fiscal year the settlement
 * month belongs to, and falls due one month later (July 30). Regression guard for the
 * hardcoded {@code LocalDate.of(2025, 6, 30)} that fossilised the FY2024/25 bulk run and
 * stamped FY2024/25 dates onto FY2025/26 settlements.
 */
class InvoiceServiceSettlementDateTest {

    @Test
    void firstMonthOfFiscalYear_datesAtThatFiscalYearsEnd() {
        assertEquals(LocalDate.of(2026, 6, 30),
                InvoiceService.settlementInvoiceDate(LocalDate.of(2025, 7, 1)));
    }

    @Test
    void lastMonthOfFiscalYear_datesAtThatFiscalYearsEnd() {
        assertEquals(LocalDate.of(2026, 6, 30),
                InvoiceService.settlementInvoiceDate(LocalDate.of(2026, 6, 1)));
    }

    @Test
    void midFiscalYearMonth_datesAtThatFiscalYearsEnd() {
        assertEquals(LocalDate.of(2026, 6, 30),
                InvoiceService.settlementInvoiceDate(LocalDate.of(2025, 12, 1)));
    }

    @Test
    void nextFiscalYear_rollsOverWithoutCodeChange() {
        assertEquals(LocalDate.of(2027, 6, 30),
                InvoiceService.settlementInvoiceDate(LocalDate.of(2026, 8, 1)));
    }

    @Test
    void priorFiscalYear_reproducesTheHistoricalConvention() {
        // Every FY2024/25 settlement in production is dated 2025-06-30.
        assertEquals(LocalDate.of(2025, 6, 30),
                InvoiceService.settlementInvoiceDate(LocalDate.of(2024, 11, 1)));
    }

    @Test
    void dueDateConvention_isOneMonthAfterFiscalYearEnd() {
        assertEquals(LocalDate.of(2026, 7, 30),
                InvoiceService.settlementInvoiceDate(LocalDate.of(2025, 7, 1)).plusMonths(1));
    }
}
