package dk.trustworks.intranet.services;

import dk.trustworks.intranet.model.PracticeLead;
import org.junit.jupiter.api.Test;

import java.time.LocalDate;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
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

    // ── practice_lead hardening (Phase 0, spec §1.6.E) ────────────────────

    @Test
    void lead_attaches_only_to_active_practice_rows() {
        assertNull(PracticeService.leadPracticeRejection("PM", "PRACTICE", true));
        assertNotNull(PracticeService.leadPracticeRejection("PM", "PRACTICE", false),
                "inactive practice must not accept leads");
        assertNotNull(PracticeService.leadPracticeRejection("UD", "SEGMENT", true),
                "SEGMENT rows (UD) must not accept leads");
    }

    @Test
    void lead_enddate_must_not_precede_startdate() {
        LocalDate start = LocalDate.of(2026, 1, 1);
        assertNotNull(PracticeService.leadEnddateRejection(start, start.minusDays(1)));
        assertNull(PracticeService.leadEnddateRejection(start, start),
                "zero-length [start, start) retracts a lead that never took effect");
        assertNull(PracticeService.leadEnddateRejection(start, start.plusDays(1)));
    }

    @Test
    void lead_overlap_rejects_same_user_overlaps_only() {
        PracticeLead open = lead("a", LocalDate.of(2025, 1, 1), null);
        PracticeLead closed = lead("b", LocalDate.of(2024, 1, 1), LocalDate.of(2024, 6, 1));

        // New open-ended lead overlapping the existing open lead.
        assertNotNull(PracticeService.leadOverlapRejection(
                LocalDate.of(2026, 1, 1), null, null, List.of(open)));
        // A period entirely before the open lead is fine.
        assertNull(PracticeService.leadOverlapRejection(
                LocalDate.of(2024, 6, 1), LocalDate.of(2024, 12, 31), null, List.of(open)));
        // Half-open adjacency: new period starting exactly at the old enddate is free.
        assertNull(PracticeService.leadOverlapRejection(
                LocalDate.of(2024, 6, 1), null, null, List.of(closed)));
        // Editing a row must not collide with itself.
        assertNull(PracticeService.leadOverlapRejection(
                LocalDate.of(2025, 1, 1), LocalDate.of(2025, 12, 31), "a", List.of(open)));
        // No rows, no conflict.
        assertNull(PracticeService.leadOverlapRejection(
                LocalDate.of(2025, 1, 1), null, null, List.of()));
    }

    @Test
    void lead_overlap_message_names_the_conflicting_row() {
        PracticeLead open = lead("lead-uuid-1", LocalDate.of(2025, 1, 1), null);
        String rejection = PracticeService.leadOverlapRejection(
                LocalDate.of(2026, 1, 1), null, null, List.of(open));
        assertNotNull(rejection);
        assertTrue(rejection.contains("lead-uuid-1"), rejection);
        assertEquals(true, rejection.contains("open"), "open-ended period rendered as 'open': " + rejection);
    }

    private static PracticeLead lead(String uuid, LocalDate startdate, LocalDate enddate) {
        return new PracticeLead(uuid, "PM", "user-1", startdate, enddate);
    }
}
