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
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

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

        // Correctness: independently re-derive the expected per-consultant aggregate using the METHOD's
        // own predicate (workduration > 0 ONLY — NOT rate > 0). This pins two things the shape
        // assertions above cannot:
        //   (a) the consultant SET must match exactly — a stray `rate > 0` filter would silently drop
        //       rate=0 (hours-fallback) consultants and shrink the result; and
        //   (b) hours == Σ workduration and revenue == Σ (workduration × rate) — catching a wrong
        //       aggregate (AVG, or dropping the × rate term).
        LocalDate periodStart = LocalDate.of(year, month, 1);
        LocalDate periodEnd = periodStart.plusMonths(1);
        @SuppressWarnings("unchecked")
        List<Tuple> expected = em.createNativeQuery("""
                SELECT wf.useruuid AS useruuid,
                       SUM(wf.workduration)            AS hours,
                       SUM(wf.workduration * wf.rate)  AS revenue
                FROM work_full wf
                WHERE wf.clientuuid = :clientUuid
                  AND wf.registered >= :periodStart
                  AND wf.registered <  :periodEnd
                  AND wf.workduration > 0
                GROUP BY wf.useruuid
                """, Tuple.class)
                .setParameter("clientUuid", clientUuid)
                .setParameter("periodStart", periodStart)
                .setParameter("periodEnd", periodEnd)
                .getResultList();

        Map<String, ConsultantWorkRevenue> actualByUser =
                rows.stream().collect(Collectors.toMap(ConsultantWorkRevenue::useruuid, r -> r));

        assertEquals(expected.size(), rows.size(),
                "method must return one row per workduration>0 consultant (no stray rate>0 filter)");
        for (Tuple e : expected) {
            String u = e.get("useruuid", String.class);
            ConsultantWorkRevenue a = actualByUser.get(u);
            assertNotNull(a, "method dropped consultant " + u + " — likely a stray rate>0 filter");
            double expHours = ((Number) e.get("hours")).doubleValue();
            double expRevenue = e.get("revenue") == null ? 0d : ((Number) e.get("revenue")).doubleValue();
            assertEquals(expHours, a.hours().doubleValue(), 0.0001, "hours mismatch for " + u);
            assertEquals(expRevenue, a.revenue().doubleValue(), 0.01, "revenue (Σ workduration×rate) mismatch for " + u);
        }
    }

    @Test
    void includesRateZeroConsultantWithZeroRevenue() {
        // Directly pin the defining behavior: a consultant whose work for a client+month is entirely
        // rate=0 (revenue 0) but has hours (workduration > 0) MUST still be returned — the method drops
        // the `rate > 0` filter on purpose (hours-fallback basis). Find such a (client, period, useruuid).
        @SuppressWarnings("unchecked")
        List<Tuple> seed = em.createNativeQuery("""
                SELECT wf.clientuuid AS clientuuid,
                       YEAR(wf.registered) AS yr,
                       MONTH(wf.registered) AS mo,
                       wf.useruuid AS useruuid
                FROM work_full wf
                WHERE wf.clientuuid IS NOT NULL AND wf.workduration > 0
                GROUP BY wf.clientuuid, YEAR(wf.registered), MONTH(wf.registered), wf.useruuid
                HAVING SUM(wf.workduration) > 0 AND SUM(wf.workduration * wf.rate) = 0
                LIMIT 1
                """, Tuple.class).getResultList();

        if (seed.isEmpty()) return; // no rate=0 consultant in the dataset — skip gracefully

        String clientUuid = seed.get(0).get("clientuuid", String.class);
        int year = ((Number) seed.get(0).get("yr")).intValue();
        int month = ((Number) seed.get(0).get("mo")).intValue();
        String rateZeroUser = seed.get(0).get("useruuid", String.class);

        List<ConsultantWorkRevenue> rows = workService.findRevenueByClientAndMonth(clientUuid, year, month);

        ConsultantWorkRevenue r = rows.stream()
                .filter(x -> rateZeroUser.equals(x.useruuid()))
                .findFirst()
                .orElse(null);

        assertNotNull(r, "rate=0 consultant " + rateZeroUser + " must NOT be dropped (no rate>0 filter)");
        assertTrue(r.hours().compareTo(BigDecimal.ZERO) > 0, "rate=0 consultant still contributes hours");
        assertEquals(0, r.revenue().compareTo(BigDecimal.ZERO), "rate=0 consultant contributes 0 revenue");
    }

    @Test
    void emptyForNonexistentClient() {
        List<ConsultantWorkRevenue> rows =
                workService.findRevenueByClientAndMonth("00000000-0000-0000-0000-000000000000", 2025, 9);
        assertTrue(rows.isEmpty());
    }
}
