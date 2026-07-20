package dk.trustworks.intranet.aggregates.executive.people;

import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PeoplePopulationSqlSupportTest {

    private static final Clock CLOCK = Clock.fixed(
            Instant.parse("2026-07-10T10:00:00Z"), ZoneId.of("Europe/Copenhagen"));

    @Test
    void ranksStatusBeforeCompanyAndPopulationFilters() {
        PeopleFilterRequest request = new PeopleFilterRequest();
        request.companyId = "00000000-0000-0000-0000-000000000001";
        PeopleFilterParams filters = PeopleFilterParams.from(request, CLOCK, TestPracticeResolver.RESOLVER);

        String sql = PeoplePopulationSqlSupport.snapshotPopulationCtes(filters, "asOfDate");

        int candidates = sql.indexOf("latest_status_candidates AS");
        int ranked = sql.indexOf("latest_status_ranked AS");
        int latest = sql.indexOf("latest_status AS");
        int filtered = sql.indexOf("filtered_population AS");
        int companyFilter = sql.indexOf("ls.companyuuid = :companyId");
        assertTrue(candidates >= 0 && candidates < ranked && ranked < latest && latest < filtered);
        assertTrue(filtered < companyFilter);
        assertFalse(sql.substring(candidates, latest).contains("companyuuid = :companyId"));
        assertTrue(sql.substring(candidates, ranked).contains("statusdate <= :asOfDate"));
    }

    @Test
    void temporalRankingUsesBusinessTransferPriorityThenAuditFallbacks() {
        PeopleFilterParams filters = PeopleFilterParams.from(new PeopleFilterRequest(), CLOCK, TestPracticeResolver.RESOLVER);
        String snapshot = PeoplePopulationSqlSupport.snapshotPopulationCtes(filters, "asOfDate");
        String monthly = PeoplePopulationSqlSupport.monthlyPopulationCtes();

        assertTrue(snapshot.contains("transfer_destination DESC,lsc.same_company_rehire DESC,"));
        assertTrue(snapshot.contains("active_from DESC,ucl.created_at DESC,ucl.uuid DESC"));
        assertTrue(snapshot.contains("paired_status.status='TERMINATED'"));
        assertTrue(snapshot.contains("NOT (paired_status.companyuuid <=> us.companyuuid)"));
        assertTrue(monthly.contains("transfer_destination DESC,msc.same_company_rehire DESC,"));
        assertTrue(monthly.contains("active_from DESC,ucl.created_at DESC,ucl.uuid DESC"));
    }

    @Test
    void managementScopesRemainSeparate() {
        PeopleFilterRequest leaderRequest = new PeopleFilterRequest();
        leaderRequest.managementScope = "PEOPLE_LEADERS";
        String leaders = PeoplePopulationSqlSupport.snapshotPopulationCtes(
                PeopleFilterParams.from(leaderRequest, CLOCK, TestPracticeResolver.RESOLVER), "asOfDate");

        PeopleFilterRequest seniorRequest = new PeopleFilterRequest();
        seniorRequest.managementScope = "SENIOR_LEADERSHIP";
        String senior = PeoplePopulationSqlSupport.snapshotPopulationCtes(
                PeopleFilterParams.from(seniorRequest, CLOCK, TestPracticeResolver.RESOLVER), "asOfDate");

        assertTrue(leaders.contains("tr.membertype = 'LEADER'"));
        assertFalse(leaders.contains("career_track IN (:seniorLeadershipTracks)"));
        assertTrue(senior.contains("career_track IN (:seniorLeadershipTracks)"));
        assertTrue(senior.contains("ls.`type` = 'CONSULTANT'"));
        assertFalse(senior.contains("tr.membertype = 'LEADER'"));
    }

    @Test
    void monthSpineCapsLiveMonthAndNeverCreatesFutureHistory() {
        String sql = PeoplePopulationSqlSupport.monthSpineCte();
        assertTrue(sql.contains("FROM dim_date"));
        assertTrue(sql.contains("THEN :asOfDate"));
        assertTrue(sql.contains("BETWEEN :periodStart AND :asOfDate"));
    }
}
