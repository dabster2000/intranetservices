package dk.trustworks.intranet.financeservice.services;

import dk.trustworks.intranet.expenseservice.remote.JournalEntryResponse;
import org.junit.jupiter.api.Test;

import java.time.LocalDate;

import static org.junit.jupiter.api.Assertions.assertEquals;

class EconomicsServiceDraftMappingTest {

    @Test
    void draftAmountUsesRawAmountForDkk() {
        JournalEntryResponse.Entry entry = new JournalEntryResponse.Entry();
        entry.amount = 1234.56;
        entry.currency = "DKK";
        entry.exchangeRate = 742.0;

        assertEquals(1234.56, EconomicsService.draftAmountInBaseCurrency(entry));
    }

    @Test
    void draftAmountConvertsForeignCurrencyUsingEconomicExchangeRateConvention() {
        JournalEntryResponse.Entry entry = new JournalEntryResponse.Entry();
        entry.amount = 100.0;
        entry.currency = "EUR";
        entry.exchangeRate = 745.25;

        assertEquals(745.25, EconomicsService.draftAmountInBaseCurrency(entry));
    }

    @Test
    void economicsPeriodMapsToFiscalStart() {
        assertEquals(LocalDate.of(2025, 7, 1), EconomicsService.fiscalStartFromEconomicsPeriod("2025_6_2026"));
    }

    @Test
    void economicsDateParserAcceptsDateTimeValues() {
        assertEquals(LocalDate.of(2026, 5, 21), EconomicsService.parseEconomicsDate("2026-05-21T00:00:00Z"));
    }
}
