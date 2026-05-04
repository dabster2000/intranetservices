package dk.trustworks.intranet.aggregates.people.services;

import dk.trustworks.intranet.aggregates.people.dto.cxo.ConsultantPyramidDTO;
import dk.trustworks.intranet.aggregates.people.dto.cxo.PyramidLevelDTO;
import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Service-level tests for {@link CxoPeopleService#consultantPyramid(Set)} mirroring
 * the BFF route at {@code /api/cxo/people/consultant-pyramid}.
 *
 * <p>The endpoint always returns 5 pyramid buckets in fixed order (Junior, Mid,
 * Senior, Leadership, Partner) regardless of which buckets have actual rows.
 * The order is preserved by the server-side {@link CxoPeopleService}'s use of
 * a fixed {@code List} of bucket descriptors.</p>
 */
@QuarkusTest
class CxoPeopleServiceConsultantPyramidTest {

    @Inject
    CxoPeopleService service;

    /**
     * Bucket targets and career-level mappings are load-bearing — they're the
     * chart's reference contract. These constants mirror the service's
     * {@code PYRAMID_BUCKETS} declaration verbatim. A refactor that silently
     * swaps target percentages between buckets (e.g. swapping Junior 30% with
     * Mid 25%) would corrupt the chart's reference line; positional bucket
     * assertions catch this.
     */
    private static final List<String> JUNIOR_LEVELS =
            List.of("JUNIOR_CONSULTANT", "PROFESSIONAL_CONSULTANT");
    private static final List<String> MID_LEVELS =
            List.of("CONSULTANT");
    private static final List<String> SENIOR_LEVELS =
            List.of("SENIOR_MANAGER", "ENGAGEMENT_MANAGER", "SENIOR_ENGAGEMENT_MANAGER", "MANAGER");
    private static final List<String> LEADERSHIP_LEVELS =
            List.of("ASSOCIATE_PARTNER", "ENGAGEMENT_DIRECTOR", "PRACTICE_LEADER",
                    "THOUGHT_LEADER_PARTNER", "MANAGING_DIRECTOR");
    private static final List<String> PARTNER_LEVELS =
            List.of("PARTNER", "MANAGING_PARTNER");

    private static void assertCanonicalBucketShape(List<PyramidLevelDTO> levels) {
        assertEquals(5, levels.size(), "Must always return exactly 5 buckets");

        PyramidLevelDTO junior = levels.get(0);
        assertEquals("Junior", junior.bucketLabel());
        assertEquals(30.0, junior.targetPercent(), 0.001,
                "Junior bucket target must be locked at 30%");
        assertEquals(JUNIOR_LEVELS, junior.careerLevels(),
                "Junior bucket careerLevels must be locked");

        PyramidLevelDTO mid = levels.get(1);
        assertEquals("Mid", mid.bucketLabel());
        assertEquals(25.0, mid.targetPercent(), 0.001,
                "Mid bucket target must be locked at 25%");
        assertEquals(MID_LEVELS, mid.careerLevels(),
                "Mid bucket careerLevels must be locked");

        PyramidLevelDTO senior = levels.get(2);
        assertEquals("Senior", senior.bucketLabel());
        assertEquals(25.0, senior.targetPercent(), 0.001,
                "Senior bucket target must be locked at 25%");
        assertEquals(SENIOR_LEVELS, senior.careerLevels(),
                "Senior bucket careerLevels must be locked");

        PyramidLevelDTO leadership = levels.get(3);
        assertEquals("Leadership", leadership.bucketLabel());
        assertEquals(15.0, leadership.targetPercent(), 0.001,
                "Leadership bucket target must be locked at 15%");
        assertEquals(LEADERSHIP_LEVELS, leadership.careerLevels(),
                "Leadership bucket careerLevels must be locked");

        PyramidLevelDTO partner = levels.get(4);
        assertEquals("Partner", partner.bucketLabel());
        assertEquals(5.0, partner.targetPercent(), 0.001,
                "Partner bucket target must be locked at 5%");
        assertEquals(PARTNER_LEVELS, partner.careerLevels(),
                "Partner bucket careerLevels must be locked");
    }

    @Test
    void consultantPyramid_noCompanyFilter_returnsList() {
        ConsultantPyramidDTO result = service.consultantPyramid(null);
        assertNotNull(result);
        assertNotNull(result.levels());
        // Bucket targets and career-level mappings are load-bearing — assert
        // each bucket's targetPercent and careerLevels set so a refactor can't
        // silently swap target percentages between buckets.
        assertCanonicalBucketShape(result.levels());

        long sumActualCount = 0;
        double sumTargetPct = 0.0;
        for (PyramidLevelDTO level : result.levels()) {
            assertNotNull(level.bucketLabel(), "bucketLabel must not be null");
            assertNotNull(level.careerLevels(), "careerLevels must not be null");
            assertTrue(level.actualCount() >= 0,
                    "actualCount must be non-negative: " + level.actualCount());
            assertTrue(level.actualPercent() >= 0 && level.actualPercent() <= 100,
                    "actualPercent in [0,100]: " + level.actualPercent());
            assertTrue(level.targetPercent() >= 0 && level.targetPercent() <= 100,
                    "targetPercent in [0,100]: " + level.targetPercent());
            sumActualCount += level.actualCount();
            sumTargetPct += level.targetPercent();
        }
        // BFF semantics: totalConsultants accumulates unconditionally for every
        // active-consultant row, but only career levels mapped to a bucket
        // contribute to the bucket counts. Therefore sum-of-bucket-counts is
        // always <= totalConsultants — the gap is the count of active
        // consultants whose career level is not mapped to any bucket
        // (e.g. SENIOR_CONSULTANT, LEAD_CONSULTANT, MANAGING_CONSULTANT,
        // PRINCIPAL_CONSULTANT).
        assertTrue(sumActualCount <= result.totalConsultants(),
                "sum of bucket counts must be <= totalConsultants (sum=" +
                sumActualCount + ", total=" + result.totalConsultants() + ")");
        // Target percents are hardcoded and sum to 100.
        assertEquals(100.0, sumTargetPct, 0.001,
                "target percents sum to 100");
        assertNotNull(result.snapshotDate(), "snapshotDate must not be null");
        assertTrue(result.snapshotDate().matches("\\d{4}-\\d{2}-\\d{2}"),
                "snapshotDate must be ISO YYYY-MM-DD: " + result.snapshotDate());
    }

    @Test
    void consultantPyramid_withCompanyFilter_unknownUuid_returnsZeroes() {
        ConsultantPyramidDTO result =
                service.consultantPyramid(Set.of("00000000-0000-0000-0000-000000000001"));
        assertNotNull(result);
        assertNotNull(result.levels());
        // Even with no actual data, target percentages and careerLevels remain
        // locked (they're independent of the company filter).
        assertCanonicalBucketShape(result.levels());
        assertEquals(0L, result.totalConsultants(), "no real company → zero consultants");
        for (PyramidLevelDTO level : result.levels()) {
            assertEquals(0L, level.actualCount(),
                    "no real company → bucket count must be zero: " + level.bucketLabel());
            assertEquals(0.0, level.actualPercent(), 0.001,
                    "no real company → bucket percent must be zero: " + level.bucketLabel());
        }
    }
}
