package dk.trustworks.intranet.aggregates.executive.people;

import org.junit.jupiter.api.Test;

import java.time.LocalDate;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;

class PeopleAnalyticsSupportTest {

    @Test
    void privacyFloorIsDisabledSoNoGroupSizeIsSuppressed() {
        // ADMIN-only full-detail view: the 1–2-person privacy floor is disabled.
        assertFalse(PeopleAnalyticsSupport.suppresses(0));
        assertFalse(PeopleAnalyticsSupport.suppresses(1));
        assertFalse(PeopleAnalyticsSupport.suppresses(2));
        assertFalse(PeopleAnalyticsSupport.suppresses(3));
        assertEquals(2L, PeopleAnalyticsSupport.visibleCount(2, false));
        assertEquals(3L, PeopleAnalyticsSupport.visibleCount(3, false));
        // An explicit response-level suppressed flag is still honoured.
        assertNull(PeopleAnalyticsSupport.visibleCount(2, true));
    }

    @Test
    void derivedPercentagesDisappearWithSuppressedInputs() {
        assertNull(PeopleAnalyticsSupport.percentage(1, 10, true));
        assertNull(PeopleAnalyticsSupport.percentage(0, 0, false));
        assertEquals(40.0d, PeopleAnalyticsSupport.percentage(4, 10, false));
    }

    @Test
    void smallExcludedPartitionIsShownWhenFloorDisabled() {
        PeopleFilterParams filters = PeopleFilterParams.from(new PeopleFilterRequest(), TestPracticeResolver.RESOLVER);
        var meta = PeopleAnalyticsSupport.meta(
                filters, LocalDate.of(2026, 7, 1), LocalDate.of(2026, 7, 10),
                null, null, 140, 2, false, null, List.of());

        assertEquals(140L, meta.sampleSize());
        assertEquals(2L, meta.excludedCount());
    }
}
