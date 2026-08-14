package dk.trustworks.intranet.recruitmentservice.services;

import org.junit.jupiter.api.Test;

import java.time.LocalDateTime;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * The pure Phase 9 sweep rules (plan §9.5): the reminder tiers with
 * their once-per-tier dedupe.
 */
class RecruitmentSchedulingOrchestratorPureTest {

    private static final LocalDateTime T0 = LocalDateTime.of(2026, 8, 17, 9, 0);

    // ---- Reminder tiers (defaults §29.16) ---------------------------------

    @Test
    void reminderTiers_fireAt24_72_96Hours() {
        assertEquals(0, tier(hoursLater(23), 0));
        assertEquals(1, tier(hoursLater(24), 0));
        assertEquals(0, tier(hoursLater(71), 1));
        assertEquals(2, tier(hoursLater(72), 1));
        assertEquals(0, tier(hoursLater(95), 2));
        assertEquals(3, tier(hoursLater(96), 2));
    }

    @Test
    void reminderTiers_fireOncePerTier() {
        // Long silence: each sweep sees the SAME census until the count
        // is bumped, so the tier function must key on nudgeCount.
        assertEquals(1, tier(hoursLater(80), 0));
        assertEquals(2, tier(hoursLater(80), 1));
        assertEquals(0, tier(hoursLater(80), 2)); // escalation not due yet
        assertEquals(3, tier(hoursLater(100), 2));
        assertEquals(0, tier(hoursLater(500), 3)); // escalated = done
    }

    @Test
    void reminderTier_toleratesMissingCreatedAt() {
        assertEquals(0, RecruitmentSchedulingOrchestrator.reminderTier(
                null, 0, hoursLater(100)));
    }

    private static int tier(LocalDateTime now, int nudgeCount) {
        return RecruitmentSchedulingOrchestrator.reminderTier(T0, nudgeCount, now);
    }

    private static LocalDateTime hoursLater(int hours) {
        return T0.plusHours(hours);
    }
}
