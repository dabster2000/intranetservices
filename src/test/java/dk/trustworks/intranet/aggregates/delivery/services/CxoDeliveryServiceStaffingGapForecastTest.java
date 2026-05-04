package dk.trustworks.intranet.aggregates.delivery.services;

import dk.trustworks.intranet.aggregates.delivery.dto.cxo.StaffingGapForecastMonthDTO;
import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import org.junit.jupiter.api.Test;

import java.time.LocalDate;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Service-level tests for
 * {@link CxoDeliveryService#staffingGapForecast(Set)} mirroring the BFF route at
 * {@code /api/cxo/delivery/staffing-gap-forecast}.
 *
 * <p>Series always returns exactly 12 rows starting at the current calendar
 * month. Supply is forward-filled from the latest non-zero supply value when a
 * future month has no row in {@code fact_user_day}. {@code gapFte} is computed
 * server-side as {@code supplyFte − demandFte}; positive = surplus,
 * negative = deficit. {@code isForecast} is {@code true} for every month after
 * the current month.</p>
 */
@QuarkusTest
class CxoDeliveryServiceStaffingGapForecastTest {

    @Inject
    CxoDeliveryService service;

    @Test
    void staffingGapForecast_noCompanyFilter_returnsList() {
        List<StaffingGapForecastMonthDTO> result = service.staffingGapForecast(null);
        assertNotNull(result);
        // Always exactly 12 rows.
        assertEquals(12, result.size(), "staffingGapForecast must return exactly 12 months");

        // First row must be the current month (today.withDayOfMonth(1)).
        LocalDate today = LocalDate.now();
        String expectedFirstKey = String.format("%04d%02d", today.getYear(), today.getMonthValue());
        assertEquals(expectedFirstKey, result.get(0).monthKey(),
                "First row must be current month");
        assertFalse(result.get(0).isForecast(),
                "Current month must have isForecast=false");

        boolean firstSeen = false;
        // Monthly progression must be strictly ascending and monthKey must agree
        // with year+monthNumber. Catches a regression where the cursor advance
        // is off-by-one or the year/month/key fields drift apart.
        String prevKey = "";
        for (StaffingGapForecastMonthDTO row : result) {
            assertNotNull(row.monthKey(), "monthKey must not be null");
            assertEquals(6, row.monthKey().length(), "monthKey must be YYYYMM");
            assertTrue(row.year() >= 2000 && row.year() <= 2100,
                    "year out of range: " + row.year());
            assertTrue(row.monthNumber() >= 1 && row.monthNumber() <= 12,
                    "monthNumber out of range: " + row.monthNumber());
            assertNotNull(row.monthLabel(), "monthLabel must not be null");

            // gap = supply - demand (computed server-side).
            assertEquals(row.supplyFte() - row.demandFte(), row.gapFte(), 1e-9,
                    "gapFte must equal supplyFte - demandFte");

            // Counts are non-negative aggregates from COUNT(DISTINCT) / SUM(consultant_count).
            assertTrue(row.supplyFte() >= 0, "supplyFte must be non-negative: " + row.supplyFte());
            assertTrue(row.demandFte() >= 0, "demandFte must be non-negative: " + row.demandFte());

            // monthKey strictly ascending.
            assertTrue(row.monthKey().compareTo(prevKey) > 0,
                    "monthKey must be strictly ascending: prev=" + prevKey
                            + " current=" + row.monthKey());
            prevKey = row.monthKey();

            // monthKey year-prefix and month-suffix must agree with the
            // numeric year/monthNumber fields.
            int yearFromKey = Integer.parseInt(row.monthKey().substring(0, 4));
            int monthFromKey = Integer.parseInt(row.monthKey().substring(4));
            assertEquals(yearFromKey, row.year(),
                    "monthKey year-prefix must match row.year()");
            assertEquals(monthFromKey, row.monthNumber(),
                    "monthKey month-suffix must match row.monthNumber()");

            // First entry is the current month → not a forecast; later entries are.
            if (!firstSeen) {
                assertEquals(false, row.isForecast(),
                        "First (current) month must have isForecast=false");
                firstSeen = true;
            } else {
                assertEquals(true, row.isForecast(),
                        "Future months must have isForecast=true");
            }
        }
    }

    @Test
    void staffingGapForecast_withCompanyFilter_unknownUuid_returnsNonNull() {
        // Random UUID will not match any real company; the supply COUNT(DISTINCT)
        // becomes 0 for those months, but the 12-row forward series shape is
        // preserved (forward-fill from initial 0 keeps all supply at 0).
        List<StaffingGapForecastMonthDTO> result =
                service.staffingGapForecast(Set.of("00000000-0000-0000-0000-000000000001"));
        assertNotNull(result);
        assertEquals(12, result.size(), "Series shape must be 12 months even when filter matches no rows");

        // With no matching companies, both supply and demand should be 0 for every month.
        for (StaffingGapForecastMonthDTO row : result) {
            assertEquals(0d, row.supplyFte(), 1e-9,
                    "Unknown company UUID must produce 0 supplyFte");
            assertEquals(0d, row.demandFte(), 1e-9,
                    "Unknown company UUID must produce 0 demandFte");
            assertEquals(0d, row.gapFte(), 1e-9,
                    "0 supply - 0 demand must produce 0 gap");
        }
    }
}
