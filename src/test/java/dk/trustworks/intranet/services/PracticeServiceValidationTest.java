package dk.trustworks.intranet.services;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * DB-free unit tests for the pure practice-write decision helpers in
 * {@link PracticeService}. Same package so the package-private statics are visible.
 */
class PracticeServiceValidationTest {

    // ── user.practice assignability ───────────────────────────────────────

    @Test
    void userPractice_null_and_blank_are_allowed() {
        assertNull(PracticeService.userPracticeRejection(null, false));
        assertNull(PracticeService.userPracticeRejection("", false));
        assertNull(PracticeService.userPracticeRejection("  ", false));
    }

    @Test
    void userPractice_ud_sentinel_is_allowed_even_without_active_row() {
        assertNull(PracticeService.userPracticeRejection("UD", false));
    }

    @Test
    void userPractice_jk_is_always_rejected() {
        String rejection = PracticeService.userPracticeRejection("JK", true);
        assertNotNull(rejection);
        assertTrue(rejection.contains("retired"), "JK rejection should mention it is retired: " + rejection);
    }

    @Test
    void userPractice_active_practice_is_allowed_inactive_is_rejected() {
        assertNull(PracticeService.userPracticeRejection("PM", true));
        String rejection = PracticeService.userPracticeRejection("PM", false);
        assertNotNull(rejection);
        assertTrue(rejection.contains("not an active practice"), rejection);
    }

    // ── team.practice_code assignability ──────────────────────────────────

    @Test
    void teamPractice_null_and_blank_clear_the_assignment() {
        assertNull(PracticeService.teamPracticeCodeRejection(null, false));
        assertNull(PracticeService.teamPracticeCodeRejection("", false));
    }

    @Test
    void teamPractice_ud_is_rejected_because_teams_use_null_not_the_sentinel() {
        // UD is a SEGMENT, not an active PRACTICE — so activePracticeRowExists=false and it is rejected.
        assertNotNull(PracticeService.teamPracticeCodeRejection("UD", false));
    }

    @Test
    void teamPractice_active_practice_is_allowed_inactive_is_rejected() {
        assertNull(PracticeService.teamPracticeCodeRejection("CYB", true));
        assertNotNull(PracticeService.teamPracticeCodeRejection("CYB", false));
    }
}
