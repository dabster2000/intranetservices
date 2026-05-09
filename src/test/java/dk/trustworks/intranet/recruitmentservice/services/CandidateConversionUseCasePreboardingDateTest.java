package dk.trustworks.intranet.recruitmentservice.services;

import org.junit.jupiter.api.Test;

import java.time.LocalDate;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * Unit tests for the package-private
 * {@link CandidateConversionUseCase#computePreboardingDate(LocalDate, LocalDate)}
 * helper. The rule:
 *
 * <ul>
 *   <li>PREBOARDING is two months before the planned start date.</li>
 *   <li>If that date would be in the past, it is clamped to {@code today}.</li>
 * </ul>
 *
 * <p>The accompanying {@code CandidateConversionUseCase.execute} caller
 * additionally skips the PREBOARDING insert when the result equals
 * {@code plannedStart} (same-day-start case) to honour the
 * {@code uq_userstatus_user_date(useruuid, statusdate)} unique key.</p>
 */
class CandidateConversionUseCasePreboardingDateTest {

    private static final LocalDate TODAY = LocalDate.of(2026, 5, 9);

    @Test
    void start_sixMonthsAhead_returnsStartMinusTwoMonths() {
        LocalDate plannedStart = TODAY.plusMonths(6);
        assertEquals(plannedStart.minusMonths(2),
                CandidateConversionUseCase.computePreboardingDate(plannedStart, TODAY));
    }

    @Test
    void start_oneMonthAhead_clampsToToday() {
        LocalDate plannedStart = TODAY.plusMonths(1);
        assertEquals(TODAY,
                CandidateConversionUseCase.computePreboardingDate(plannedStart, TODAY));
    }

    @Test
    void start_today_clampsToToday() {
        assertEquals(TODAY,
                CandidateConversionUseCase.computePreboardingDate(TODAY, TODAY));
    }

    @Test
    void start_thirtyDaysAgo_clampsToToday() {
        LocalDate plannedStart = TODAY.minusDays(30);
        assertEquals(TODAY,
                CandidateConversionUseCase.computePreboardingDate(plannedStart, TODAY));
    }

    @Test
    void start_exactlyTwoMonthsAhead_clampsToToday() {
        LocalDate plannedStart = TODAY.plusMonths(2);
        assertEquals(TODAY,
                CandidateConversionUseCase.computePreboardingDate(plannedStart, TODAY));
    }

    @Test
    void nullPlannedStart_throwsNpeWithMessage() {
        NullPointerException npe = assertThrows(NullPointerException.class,
                () -> CandidateConversionUseCase.computePreboardingDate(null, TODAY));
        assertEquals("plannedStart must not be null", npe.getMessage());
    }

    @Test
    void nullToday_throwsNpeWithMessage() {
        NullPointerException npe = assertThrows(NullPointerException.class,
                () -> CandidateConversionUseCase.computePreboardingDate(TODAY, null));
        assertEquals("today must not be null", npe.getMessage());
    }
}
