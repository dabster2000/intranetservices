package dk.trustworks.intranet.services;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * DB-free unit tests for the pure IT-budget resolution helper in
 * {@link TeamSettingService}: no team -> default; one team -> its value; many
 * teams -> MAX. Same package so the package-private static is visible.
 */
class TeamSettingServiceTest {

    private static final int DEFAULT = TeamSettingService.DEFAULT_IT_BUDGET;

    @Test
    void null_or_empty_falls_back_to_default() {
        assertEquals(DEFAULT, TeamSettingService.resolveBudget(null, DEFAULT));
        assertEquals(DEFAULT, TeamSettingService.resolveBudget(List.of(), DEFAULT));
    }

    @Test
    void single_team_uses_its_value() {
        assertEquals(32000, TeamSettingService.resolveBudget(List.of(32000), DEFAULT));
        assertEquals(0, TeamSettingService.resolveBudget(List.of(0), DEFAULT));
    }

    @Test
    void multiple_teams_take_the_maximum() {
        assertEquals(32000, TeamSettingService.resolveBudget(List.of(25000, 32000), DEFAULT));
        assertEquals(25000, TeamSettingService.resolveBudget(List.of(0, 25000, 0), DEFAULT));
    }

    @Test
    void default_constant_matches_the_re_homed_value() {
        assertEquals(25000, TeamSettingService.DEFAULT_IT_BUDGET);
    }
}
