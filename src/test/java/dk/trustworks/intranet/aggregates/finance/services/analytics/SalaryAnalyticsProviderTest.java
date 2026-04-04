package dk.trustworks.intranet.aggregates.finance.services.analytics;

import dk.trustworks.intranet.aggregates.finance.dto.analytics.SalaryByBandDTO;
import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import org.junit.jupiter.api.Test;

import java.time.LocalDate;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

@QuarkusTest
class SalaryAnalyticsProviderTest {

    @Inject
    SalaryAnalyticsProvider provider;

    @Test
    void getAvgSalaryByBand_returnsResults() {
        LocalDate from = LocalDate.of(2025, 1, 1);
        LocalDate to = LocalDate.of(2025, 6, 30);
        List<SalaryByBandDTO> result = provider.getAvgSalaryByBand(from, to, null);

        assertNotNull(result);
        assertFalse(result.isEmpty(), "Should return salary data for at least one band/month");

        // Verify structure
        SalaryByBandDTO first = result.getFirst();
        assertNotNull(first.monthKey());
        assertNotNull(first.careerBand());
        assertTrue(first.salaryDkk() > 0, "Salary should be positive");
        assertTrue(first.consultantCount() > 0, "Should have at least one consultant");
        assertTrue(CareerBandMapper.BAND_ORDER.contains(first.careerBand())
                || "Unknown".equals(first.careerBand()),
                "Career band should be one of the 6 known bands or Unknown");
    }

    @Test
    void getTotalSalaryByBand_sumsAreGreaterThanAverages() {
        LocalDate from = LocalDate.of(2025, 1, 1);
        LocalDate to = LocalDate.of(2025, 3, 31);
        List<SalaryByBandDTO> avgs = provider.getAvgSalaryByBand(from, to, null);
        List<SalaryByBandDTO> totals = provider.getTotalSalaryByBand(from, to, null);

        // For any band with count > 1, total should be > avg
        for (SalaryByBandDTO avg : avgs) {
            if (avg.consultantCount() > 1) {
                SalaryByBandDTO matchingTotal = totals.stream()
                        .filter(t -> t.monthKey().equals(avg.monthKey()) && t.careerBand().equals(avg.careerBand()))
                        .findFirst().orElse(null);
                assertNotNull(matchingTotal, "Total should have matching band/month");
                assertTrue(matchingTotal.salaryDkk() > avg.salaryDkk(),
                        "Total salary should exceed average when count > 1");
            }
        }
    }

    @Test
    void getAvgSalaryByBand_monthLabelsAreFormatted() {
        LocalDate from = LocalDate.of(2025, 1, 1);
        LocalDate to = LocalDate.of(2025, 1, 31);
        List<SalaryByBandDTO> result = provider.getAvgSalaryByBand(from, to, null);

        if (!result.isEmpty()) {
            assertTrue(result.getFirst().monthLabel().startsWith("Jan"),
                    "January month label should start with 'Jan'");
        }
    }

    @Test
    void formatMonthLabel_producesExpectedFormat() {
        assertEquals("Jan 2025", SalaryAnalyticsProvider.formatMonthLabel(2025, 1));
        assertEquals("Jul 2026", SalaryAnalyticsProvider.formatMonthLabel(2026, 7));
        assertEquals("Dec 2024", SalaryAnalyticsProvider.formatMonthLabel(2024, 12));
    }
}
