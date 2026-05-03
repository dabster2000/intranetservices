package dk.trustworks.intranet.aggregates.executive.services;

import dk.trustworks.intranet.aggregates.executive.dto.people.ExecRetentionCohortDTO;
import dk.trustworks.intranet.aggregates.executive.dto.people.ExecRetentionCohortPointDTO;
import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Service-level tests for {@link ExecutivePeopleService#retentionCohorts(Set)}
 * mirroring the BFF route at {@code /api/executive/retention-cohorts}.
 *
 * <p>Cohorts span 2019–2025; each contains 9 time-points
 * {0, 6, 12, 18, 24, 36, 48, 60, 72}. {@code survivalPct} is right-censored
 * (null) for time points that exceed months elapsed since cohort Jan 1.</p>
 */
@QuarkusTest
class ExecutivePeopleServiceRetentionCohortsTest {

    private static final List<Integer> TIME_POINTS = List.of(0, 6, 12, 18, 24, 36, 48, 60, 72);

    @Inject
    ExecutivePeopleService service;

    @Test
    void retentionCohorts_noCompanyFilter_returnsList() {
        List<ExecRetentionCohortDTO> result = service.retentionCohorts(null);
        assertNotNull(result);
        // BFF returns one cohort per year in [2019, 2025] regardless of data
        assertEquals(7, result.size(), "Must return exactly 7 cohorts (2019..2025)");
        int prevYear = 2018;
        for (ExecRetentionCohortDTO cohort : result) {
            assertTrue(cohort.cohortYear() >= 2019 && cohort.cohortYear() <= 2025,
                    "cohortYear out of range: " + cohort.cohortYear());
            assertTrue(cohort.cohortYear() > prevYear,
                    "cohorts must be ascending by year");
            prevYear = cohort.cohortYear();
            assertTrue(cohort.cohortSize() >= 0, "cohortSize must be non-negative");
            assertNotNull(cohort.points(), "points must not be null");
            assertEquals(9, cohort.points().size(), "Must have 9 time points");
            for (int i = 0; i < cohort.points().size(); i++) {
                ExecRetentionCohortPointDTO pt = cohort.points().get(i);
                assertEquals(TIME_POINTS.get(i).intValue(), pt.monthsSinceHire(),
                        "monthsSinceHire must match expected time point at index " + i);
                if (pt.survivalPct() != null) {
                    assertTrue(pt.survivalPct() >= 0.0 && pt.survivalPct() <= 100.0,
                            "survivalPct out of [0, 100]: " + pt.survivalPct());
                }
            }
            // Empty cohort → all survival pcts null (matches BFF)
            if (cohort.cohortSize() == 0) {
                for (ExecRetentionCohortPointDTO pt : cohort.points()) {
                    assertEquals(null, pt.survivalPct(),
                            "empty cohort must have all null survivalPct");
                }
            }
        }
    }

    @Test
    void retentionCohorts_withCompanyFilter_unknownUuid_returnsEmpty() {
        // BFF behavior: with a company filter that matches no rows, all 7 cohorts
        // are still returned but with cohortSize=0 and all null survivalPcts.
        List<ExecRetentionCohortDTO> result =
                service.retentionCohorts(Set.of("00000000-0000-0000-0000-000000000001"));
        assertNotNull(result);
        assertEquals(7, result.size(), "All 7 cohort years are always returned");
        for (ExecRetentionCohortDTO cohort : result) {
            assertEquals(0L, cohort.cohortSize(),
                    "cohortSize must be 0 for unknown company filter");
            for (ExecRetentionCohortPointDTO pt : cohort.points()) {
                assertEquals(null, pt.survivalPct(),
                        "empty cohort must have null survivalPct at all time points");
            }
        }
    }
}
