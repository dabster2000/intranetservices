package dk.trustworks.intranet.aggregates.finance.services;

import dk.trustworks.intranet.aggregates.finance.dto.TeamSalaryBandDTO;
import dk.trustworks.intranet.aggregates.finance.services.TeamPeopleService.TeamMemberSalaryRow;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Unit tests for {@link TeamPeopleService#buildSalaryBands} — specifically the small-n
 * suppression that keeps individually identifiable salaries out of the dashboard:read
 * boundary (audit finding M1: with n≤2 company-wide, min/max IS one person's exact salary).
 */
class TeamPeopleServiceSalaryBandTest {

    private static TeamMemberSalaryRow member(String userId, String level, int salary) {
        return new TeamMemberSalaryRow(userId, "First-" + userId, "Last-" + userId, level, salary);
    }

    @Test
    void suppressesBandWhenCompanySampleIsBelowThreshold() {
        // MANAGER has a single consultant company-wide → min = max = that person's exact salary
        Map<String, List<Integer>> companySalaries = Map.of("MANAGER", List.of(57_500));
        Map<String, String> tracks = Map.of("MANAGER", "DELIVERY");
        Map<String, List<TeamMemberSalaryRow>> teamByLevel =
                Map.of("MANAGER", List.of(member("u1", "MANAGER", 57_500)));

        List<TeamSalaryBandDTO> bands = TeamPeopleService.buildSalaryBands(
                List.of("MANAGER"), companySalaries, tracks, teamByLevel);

        assertTrue(bands.isEmpty(), "n=1 band must be suppressed — its stats identify one person's salary");
    }

    @Test
    void suppressesBandJustBelowThresholdAndKeepsBandAtThreshold() {
        // PARTNER: n=4 (audit example — all four partners at the same salary) → suppressed.
        // CONSULTANT: n=5 → emitted.
        Map<String, List<Integer>> companySalaries = Map.of(
                "PARTNER", List.of(120_000, 120_000, 120_000, 120_000),
                "CONSULTANT", List.of(40_000, 42_000, 44_000, 46_000, 48_000));
        Map<String, String> tracks = Map.of("PARTNER", "NONE", "CONSULTANT", "DELIVERY");
        Map<String, List<TeamMemberSalaryRow>> teamByLevel = Map.of(
                "PARTNER", List.of(member("p1", "PARTNER", 120_000)),
                "CONSULTANT", List.of(member("c1", "CONSULTANT", 44_000)));

        List<TeamSalaryBandDTO> bands = TeamPeopleService.buildSalaryBands(
                List.of("PARTNER", "CONSULTANT"), companySalaries, tracks, teamByLevel);

        assertEquals(1, bands.size(), "only the n>=5 band may be emitted");
        TeamSalaryBandDTO band = bands.get(0);
        assertEquals("CONSULTANT", band.careerLevel());
        assertEquals(5, band.companyWideCount());
        assertEquals(40_000, band.minSalary());
        assertEquals(48_000, band.maxSalary());
        assertEquals(44_000, band.p50());
        assertEquals(1, band.members().size());
        assertEquals("c1", band.members().get(0).userId());
        assertEquals(44_000, band.members().get(0).salary());
        // c1 is the middle of five salaries → percentile rank (2 + 0.5) / 5 = 50%
        assertEquals(50.0, band.members().get(0).percentileRank(), 0.01);
    }

    @Test
    void emitsNothingWhenLevelHasNoCompanyData() {
        List<TeamSalaryBandDTO> bands = TeamPeopleService.buildSalaryBands(
                List.of("UNKNOWN_LEVEL"), Map.of(), Map.of(),
                Map.of("UNKNOWN_LEVEL", List.of(member("u1", "UNKNOWN_LEVEL", 50_000))));

        assertTrue(bands.isEmpty());
    }
}
