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

    @Test
    void consultantPyramid_noCompanyFilter_returnsList() {
        ConsultantPyramidDTO result = service.consultantPyramid(null);
        assertNotNull(result);
        assertNotNull(result.levels());
        assertEquals(5, result.levels().size(),
                "must return 5 pyramid buckets in fixed order");
        // Bucket labels in fixed order.
        assertEquals("Junior", result.levels().get(0).bucketLabel());
        assertEquals("Mid", result.levels().get(1).bucketLabel());
        assertEquals("Senior", result.levels().get(2).bucketLabel());
        assertEquals("Leadership", result.levels().get(3).bucketLabel());
        assertEquals("Partner", result.levels().get(4).bucketLabel());

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
        // sum of bucket counts should match totalConsultants (BFF semantics:
        // unknown career levels excluded from BOTH counts and totalConsultants).
        assertEquals(result.totalConsultants(), sumActualCount,
                "sum of bucket counts must equal totalConsultants");
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
        // Buckets are always returned, just with zero counts.
        assertEquals(5, result.levels().size());
        assertEquals(0L, result.totalConsultants(), "no real company → zero consultants");
        for (PyramidLevelDTO level : result.levels()) {
            assertEquals(0L, level.actualCount(),
                    "no real company → bucket count must be zero: " + level.bucketLabel());
            assertEquals(0.0, level.actualPercent(), 0.001,
                    "no real company → bucket percent must be zero: " + level.bucketLabel());
        }
    }
}
