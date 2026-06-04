package dk.trustworks.intranet.dao.workservice.services;

import dk.trustworks.intranet.dto.work.ConsultantWorkRevenue;
import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.junit.TestProfile;
import io.quarkus.test.junit.QuarkusTestProfile;
import jakarta.inject.Inject;
import jakarta.persistence.EntityManager;
import jakarta.persistence.Tuple;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

@QuarkusTest
@TestProfile(WorkServiceClientRevenueTest.NoDevServicesProfile.class)
class WorkServiceClientRevenueTest {

    public static class NoDevServicesProfile implements QuarkusTestProfile {
        @Override
        public Map<String, String> getConfigOverrides() {
            return Map.of(
                    "quarkus.s3.devservices.enabled", "false",
                    "cvtool.username", "test-placeholder",
                    "cvtool.password", "test-placeholder"
            );
        }
    }

    @Inject
    WorkService workService;

    @Inject
    EntityManager em;

    @Test
    void returnsPerConsultantHoursAndRevenue_forAClientWithWork() {
        // Find a (clientuuid, year, month) that actually has billable work in work_full.
        @SuppressWarnings("unchecked")
        List<Tuple> seed = em.createNativeQuery("""
                SELECT wf.clientuuid AS clientuuid,
                       YEAR(wf.registered) AS yr,
                       MONTH(wf.registered) AS mo
                FROM work_full wf
                WHERE wf.clientuuid IS NOT NULL AND wf.workduration > 0 AND wf.rate > 0
                GROUP BY wf.clientuuid, YEAR(wf.registered), MONTH(wf.registered)
                HAVING COUNT(DISTINCT wf.useruuid) >= 1
                LIMIT 1
                """, Tuple.class).getResultList();

        if (seed.isEmpty()) return; // no data locally — skip gracefully

        String clientUuid = seed.get(0).get("clientuuid", String.class);
        int year = ((Number) seed.get(0).get("yr")).intValue();
        int month = ((Number) seed.get(0).get("mo")).intValue();

        List<ConsultantWorkRevenue> rows = workService.findRevenueByClientAndMonth(clientUuid, year, month);

        assertFalse(rows.isEmpty(), "expected at least one consultant row");
        for (ConsultantWorkRevenue r : rows) {
            assertNotNull(r.useruuid());
            assertNotNull(r.hours());
            assertNotNull(r.revenue());
            assertTrue(r.hours().compareTo(BigDecimal.ZERO) > 0, "hours must be > 0 (workduration > 0 filter)");
            assertTrue(r.revenue().compareTo(BigDecimal.ZERO) >= 0, "revenue must be >= 0");
        }
        // useruuid must be distinct (GROUP BY useruuid)
        long distinct = rows.stream().map(ConsultantWorkRevenue::useruuid).distinct().count();
        assertEquals(rows.size(), distinct, "one row per consultant");
    }

    @Test
    void emptyForNonexistentClient() {
        List<ConsultantWorkRevenue> rows =
                workService.findRevenueByClientAndMonth("00000000-0000-0000-0000-000000000000", 2025, 9);
        assertTrue(rows.isEmpty());
    }
}
