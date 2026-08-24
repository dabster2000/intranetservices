package dk.trustworks.intranet.recruitmentservice.resources;

import jakarta.ws.rs.WebApplicationException;
import org.junit.jupiter.api.Test;

import java.time.LocalDate;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * The {@code from} anchor of {@code /recruitment/interviews/suggested-slots}:
 * absent → today, elapsed → clamped to today (a past anchor only spends a
 * Graph probe on days nobody can book), absurdly far out → 400, malformed
 * → 400. Pure unit test on the resource's static helper, so it runs in the
 * DB-free tier that gates deploys.
 */
class RecruitmentSuggestionWindowTest {

    private static final LocalDate TODAY = LocalDate.of(2026, 8, 24);

    @Test
    void absentOrBlank_defaultsToToday() {
        assertEquals(TODAY, RecruitmentInterviewResource.resolveSuggestionFrom(null, TODAY));
        assertEquals(TODAY, RecruitmentInterviewResource.resolveSuggestionFrom("  ", TODAY));
    }

    @Test
    void elapsedAnchor_isClampedToToday_notProbedForNothing() {
        assertEquals(TODAY,
                RecruitmentInterviewResource.resolveSuggestionFrom("2026-08-17", TODAY),
                "a week of elapsed days would be scanned only to be discarded");
        assertEquals(TODAY,
                RecruitmentInterviewResource.resolveSuggestionFrom("2025-01-01", TODAY));
    }

    @Test
    void todayAndFutureAnchors_passThroughUnchanged() {
        assertEquals(TODAY, RecruitmentInterviewResource.resolveSuggestionFrom("2026-08-24", TODAY));
        assertEquals(LocalDate.of(2026, 9, 14),
                RecruitmentInterviewResource.resolveSuggestionFrom("2026-09-14", TODAY),
                "three weeks out is past the longest real interview lead time");
    }

    @Test
    void theLastAcceptedDay_isTheBoundaryItself() {
        LocalDate boundary = TODAY.plusDays(180);
        assertEquals(boundary,
                RecruitmentInterviewResource.resolveSuggestionFrom(boundary.toString(), TODAY));

        WebApplicationException rejected = assertThrows(WebApplicationException.class, () ->
                RecruitmentInterviewResource.resolveSuggestionFrom(
                        boundary.plusDays(1).toString(), TODAY));
        assertEquals(400, rejected.getResponse().getStatus());
    }

    @Test
    void malformedAnchor_keepsTheIsoDate400() {
        WebApplicationException rejected = assertThrows(WebApplicationException.class, () ->
                RecruitmentInterviewResource.resolveSuggestionFrom("24-08-2026", TODAY));
        assertEquals(400, rejected.getResponse().getStatus());
    }
}
