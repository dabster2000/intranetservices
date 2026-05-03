package dk.trustworks.intranet.aggregates.executive.services;

import dk.trustworks.intranet.aggregates.executive.dto.people.ExecCareerLevelDistDTO;
import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import org.junit.jupiter.api.Test;

import java.util.HashSet;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Service-level tests for
 * {@link ExecutivePeopleService#careerLevelDistribution(Set)} mirroring the
 * BFF route at {@code /api/executive/career-level-distribution}.
 *
 * <p>Returns all 18 canonical career levels with count = 0 for levels with no
 * active consultants — matches BFF semantics regardless of data. Each entry
 * carries its hardcoded {@code careerTrack} grouping.</p>
 */
@QuarkusTest
class ExecutivePeopleServiceCareerLevelDistributionTest {

    private static final Set<String> EXPECTED_LEVELS = Set.of(
            "JUNIOR_CONSULTANT", "CONSULTANT", "PROFESSIONAL_CONSULTANT", "SENIOR_CONSULTANT",
            "LEAD_CONSULTANT", "MANAGING_CONSULTANT", "PRINCIPAL_CONSULTANT",
            "MANAGER", "SENIOR_MANAGER", "ASSOCIATE_PARTNER",
            "ENGAGEMENT_MANAGER", "SENIOR_ENGAGEMENT_MANAGER", "ENGAGEMENT_DIRECTOR",
            "PARTNER", "THOUGHT_LEADER_PARTNER", "PRACTICE_LEADER",
            "MANAGING_DIRECTOR", "MANAGING_PARTNER"
    );

    private static final Set<String> EXPECTED_TRACKS = Set.of(
            "Entry", "Delivery", "Advisory", "Leadership", "Client Engagement", "Partner / Director"
    );

    @Inject
    ExecutivePeopleService service;

    @Test
    void careerLevelDistribution_noCompanyFilter_returnsList() {
        List<ExecCareerLevelDistDTO> result = service.careerLevelDistribution(null);
        assertNotNull(result);
        assertEquals(18, result.size(), "Must return all 18 canonical career levels");
        Set<String> seen = new HashSet<>();
        for (ExecCareerLevelDistDTO row : result) {
            assertNotNull(row.careerLevel(), "careerLevel must not be null");
            assertTrue(EXPECTED_LEVELS.contains(row.careerLevel()),
                    "Unexpected careerLevel: " + row.careerLevel());
            assertTrue(seen.add(row.careerLevel()),
                    "Duplicate careerLevel: " + row.careerLevel());
            assertTrue(EXPECTED_TRACKS.contains(row.careerTrack()),
                    "Unexpected careerTrack: " + row.careerTrack());
            assertTrue(row.count() >= 0, "count must be non-negative: " + row.count());
        }
    }

    @Test
    void careerLevelDistribution_withCompanyFilter_unknownUuid_returnsEmpty() {
        // BFF behaviour: unknown company filter still returns all 18 levels
        // with count=0 (the levels are emitted regardless of data).
        List<ExecCareerLevelDistDTO> result =
                service.careerLevelDistribution(Set.of("00000000-0000-0000-0000-000000000001"));
        assertNotNull(result);
        assertEquals(18, result.size(), "All 18 levels are always returned");
        for (ExecCareerLevelDistDTO row : result) {
            assertEquals(0L, row.count(), "Unknown UUID must yield count=0 everywhere");
        }
    }
}
