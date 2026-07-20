package dk.trustworks.intranet.utils;

import org.junit.jupiter.api.Test;

import java.time.LocalDate;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Unit tests for {@link DateUtils#periodsOverlap} — the half-open [start, end)
 * interval overlap backing the canonical temporal predicate (practice spec §4.2:
 * current as-of D ⇔ startdate <= D AND (enddate IS NULL OR enddate > D)).
 */
class DateUtilsPeriodsOverlapTest {

    private static final LocalDate JAN = LocalDate.of(2026, 1, 1);
    private static final LocalDate FEB = LocalDate.of(2026, 2, 1);
    private static final LocalDate MAR = LocalDate.of(2026, 3, 1);
    private static final LocalDate APR = LocalDate.of(2026, 4, 1);

    @Test
    void bounded_periods_overlap_when_they_share_days() {
        assertTrue(DateUtils.periodsOverlap(JAN, MAR, FEB, APR));
        assertTrue(DateUtils.periodsOverlap(FEB, APR, JAN, MAR));
        assertTrue(DateUtils.periodsOverlap(JAN, APR, FEB, MAR), "containment overlaps");
    }

    @Test
    void adjacent_periods_do_not_overlap() {
        // Half-open: [JAN, FEB) and [FEB, MAR) share no day.
        assertFalse(DateUtils.periodsOverlap(JAN, FEB, FEB, MAR));
        assertFalse(DateUtils.periodsOverlap(FEB, MAR, JAN, FEB));
    }

    @Test
    void disjoint_periods_do_not_overlap() {
        assertFalse(DateUtils.periodsOverlap(JAN, FEB, MAR, APR));
    }

    @Test
    void null_end_means_open_ended() {
        assertTrue(DateUtils.periodsOverlap(JAN, null, MAR, APR));
        assertTrue(DateUtils.periodsOverlap(JAN, null, MAR, null), "two open periods always overlap");
        assertFalse(DateUtils.periodsOverlap(MAR, null, JAN, MAR), "open period starting at the other's end");
    }

    @Test
    void null_start_means_since_forever() {
        assertTrue(DateUtils.periodsOverlap(null, FEB, JAN, MAR));
        assertFalse(DateUtils.periodsOverlap(null, JAN, JAN, MAR), "ends exactly where the other starts");
    }

    @Test
    void zero_length_period_overlaps_nothing() {
        assertFalse(DateUtils.periodsOverlap(FEB, FEB, JAN, MAR), "[FEB, FEB) is empty");
    }
}
