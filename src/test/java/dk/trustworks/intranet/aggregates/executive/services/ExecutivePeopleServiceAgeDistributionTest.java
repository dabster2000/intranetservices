package dk.trustworks.intranet.aggregates.executive.services;

import dk.trustworks.intranet.aggregates.executive.dto.people.ExecAgeBucketDTO;
import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Service-level tests for {@link ExecutivePeopleService#ageDistribution(Set)}
 * mirroring the BFF route at {@code /api/executive/age-distribution}.
 *
 * <p>Snapshot of active employees ({@code type IN ('CONSULTANT', 'STUDENT', 'STAFF')},
 * {@code status='ACTIVE'}) with valid birthday, bucketed in 5-year increments by
 * gender. Buckets are sorted ascending by {@code bucketStart}.</p>
 */
@QuarkusTest
class ExecutivePeopleServiceAgeDistributionTest {

    @Inject
    ExecutivePeopleService service;

    @Test
    void ageDistribution_noCompanyFilter_returnsList() {
        List<ExecAgeBucketDTO> result = service.ageDistribution(null);
        assertNotNull(result);
        int prevBucketStart = -1;
        for (ExecAgeBucketDTO row : result) {
            assertNotNull(row.bucket(), "bucket label must not be null");
            assertTrue(row.bucketStart() >= 0 && row.bucketStart() <= 200,
                    "bucketStart out of range: " + row.bucketStart());
            assertTrue(row.bucketStart() > prevBucketStart,
                    "bucketStart must be strictly ascending: prev=" + prevBucketStart
                            + " current=" + row.bucketStart());
            prevBucketStart = row.bucketStart();
            assertTrue(row.maleCount() >= 0, "maleCount must be non-negative");
            assertTrue(row.femaleCount() >= 0, "femaleCount must be non-negative");
            assertTrue(row.unknownCount() >= 0, "unknownCount must be non-negative");
            assertEquals(row.maleCount() + row.femaleCount() + row.unknownCount(),
                    row.total(),
                    "total must equal maleCount + femaleCount + unknownCount");
            assertEquals(row.bucketStart() + "–" + (row.bucketStart() + 4),
                    row.bucket(), "bucket label format must be N–(N+4)");
        }
    }

    @Test
    void ageDistribution_withCompanyFilter_unknownUuid_returnsEmpty() {
        List<ExecAgeBucketDTO> result =
                service.ageDistribution(Set.of("00000000-0000-0000-0000-000000000001"));
        assertNotNull(result);
        assertTrue(result.isEmpty(), "Random UUID must not match any real company");
    }
}
