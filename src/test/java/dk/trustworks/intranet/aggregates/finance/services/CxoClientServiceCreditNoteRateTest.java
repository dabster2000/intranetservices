package dk.trustworks.intranet.aggregates.finance.services;

import dk.trustworks.intranet.aggregates.finance.dto.cxo.CreditNoteRateDTO;
import dk.trustworks.intranet.aggregates.finance.dto.cxo.CreditNoteTopClientDTO;
import dk.trustworks.intranet.aggregates.finance.dto.cxo.MonthlyCreditNoteDTO;
import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import org.junit.jupiter.api.Test;

import java.time.LocalDate;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

@QuarkusTest
class CxoClientServiceCreditNoteRateTest {

    @Inject
    CxoClientService service;

    @Test
    void creditNoteRate_defaultRange_returnsWrapper() {
        CreditNoteRateDTO result = service.creditNoteRate(null, null, null);
        assertNotNull(result);
        assertNotNull(result.monthly());
        assertNotNull(result.topClients());
        assertTrue(result.topClients().size() <= 5, "Top clients capped at 5");
        for (MonthlyCreditNoteDTO m : result.monthly()) {
            assertNotNull(m.monthKey());
            assertEquals(6, m.monthKey().length());
            assertTrue(m.year() >= 2020 && m.year() <= 2030);
            assertTrue(m.monthNumber() >= 1 && m.monthNumber() <= 12);
            assertTrue(m.creditNoteRatePct() >= 0.0);
            assertTrue(m.invoiceAmountDkk() >= 0.0);
            assertTrue(m.creditNoteAmountDkk() >= 0.0);
        }
        for (CreditNoteTopClientDTO c : result.topClients()) {
            assertNotNull(c.clientName());
            assertTrue(c.creditNoteAmountDkk() > 0);
            assertTrue(c.creditNoteCount() > 0);
        }
        for (int i = 1; i < result.topClients().size(); i++) {
            assertTrue(
                result.topClients().get(i - 1).creditNoteAmountDkk() >= result.topClients().get(i).creditNoteAmountDkk(),
                "topClients must be sorted by creditNoteAmountDkk DESC at index " + i);
        }
    }

    @Test
    void creditNoteRate_explicitRange_doesNotThrow() {
        CreditNoteRateDTO result = service.creditNoteRate(
                LocalDate.of(2024, 1, 1), LocalDate.of(2024, 12, 31), null);
        assertNotNull(result);
    }

    @Test
    void creditNoteRate_withCompanyFilter_doesNotThrow() {
        Set<String> companyIds = Set.of("00000000-0000-0000-0000-000000000001");
        CreditNoteRateDTO result = service.creditNoteRate(null, null, companyIds);
        assertNotNull(result);
        assertTrue(result.monthly().isEmpty(), "filter with random UUIDs should return empty");
        assertTrue(result.topClients().isEmpty(), "filter with random UUIDs should return empty");
    }
}
