package dk.trustworks.intranet.aggregates.executive.people;

import org.junit.jupiter.api.Test;

import java.time.LocalDate;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PeopleAnalyticsSupportTest {

    @Test
    void privacyFloorIsThreeAndZeroIsSafe() {
        assertFalse(PeopleAnalyticsSupport.suppresses(0));
        assertTrue(PeopleAnalyticsSupport.suppresses(1));
        assertTrue(PeopleAnalyticsSupport.suppresses(2));
        assertFalse(PeopleAnalyticsSupport.suppresses(3));
        assertNull(PeopleAnalyticsSupport.visibleCount(2, false));
        assertEquals(3L, PeopleAnalyticsSupport.visibleCount(3, false));
    }

    @Test
    void derivedPercentagesDisappearWithSuppressedInputs() {
        assertNull(PeopleAnalyticsSupport.percentage(1, 10, true));
        assertNull(PeopleAnalyticsSupport.percentage(0, 0, false));
        assertEquals(40.0d, PeopleAnalyticsSupport.percentage(4, 10, false));
    }

    @Test
    void smallExcludedPartitionAlsoHidesEligibleSample() {
        PeopleFilterParams filters = PeopleFilterParams.from(new PeopleFilterRequest());
        var meta = PeopleAnalyticsSupport.meta(
                filters, LocalDate.of(2026, 7, 1), LocalDate.of(2026, 7, 10),
                null, null, 140, 2, false, null, List.of());

        assertNull(meta.sampleSize());
        assertNull(meta.excludedCount());
    }
}
