package dk.trustworks.intranet.recruitmentservice.notifications;

import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The reschedule kit-DM plan (plan Phase 2.4 notification correctness):
 * "Interview moved" used to go to EVERY interviewer even when only the
 * list changed. Now: details changed → moved-DM to all current;
 * list-only change → "you've been added" to the additions alone;
 * list-only removal → silence. Plain unit test in the DB-free tier.
 */
class RecruitmentSlackReactorDmPlanTest {

    private static final List<String> OLD = List.of("a", "b");

    @Test
    void detailsChanged_everyCurrentInterviewerGetsTheMovedDm() {
        RecruitmentSlackReactor.KitDmPlan plan =
                RecruitmentSlackReactor.rescheduleDmPlan(List.of("a", "b", "c"), OLD, true);

        assertFalse(plan.addedOnly());
        assertEquals(List.of("a", "b", "c"), plan.recipients(),
                "additions get the moved-DM too — the new details matter most to them");
    }

    @Test
    void listOnlyChange_additionsAloneGetTheAddedDm() {
        RecruitmentSlackReactor.KitDmPlan plan =
                RecruitmentSlackReactor.rescheduleDmPlan(List.of("a", "b", "c"), OLD, false);

        assertTrue(plan.addedOnly());
        assertEquals(List.of("c"), plan.recipients(),
                "a and b already have their kit — nothing changed for them");
    }

    @Test
    void listOnlyRemoval_nobodyIsPinged() {
        RecruitmentSlackReactor.KitDmPlan plan =
                RecruitmentSlackReactor.rescheduleDmPlan(List.of("a"), OLD, false);

        assertTrue(plan.recipients().isEmpty());
    }

    @Test
    void legacyEventWithoutPreviousList_fallsBackToMovedForAll() {
        // Pre-diff events (and their catch-up retries) carry no
        // previous_interviewer_uuids — the old DM-everyone behavior stands.
        RecruitmentSlackReactor.KitDmPlan plan =
                RecruitmentSlackReactor.rescheduleDmPlan(List.of("a", "b"), null, false);

        assertFalse(plan.addedOnly());
        assertEquals(List.of("a", "b"), plan.recipients());
    }

    // ---- detailsChanged --------------------------------------------------------

    @Test
    void timeChange_isADetailChange() {
        Map<String, Object> payload = samePayload();
        payload.put("scheduled_at", "2026-08-21T13:00");
        assertTrue(RecruitmentSlackReactor.rescheduleDetailsChanged(payload));
    }

    @Test
    void locationRoomOrDurationChange_isADetailChange() {
        Map<String, Object> location = samePayload();
        location.put("location", "HQ meeting room 3");
        assertTrue(RecruitmentSlackReactor.rescheduleDetailsChanged(location));

        Map<String, Object> room = samePayload();
        room.put("room_email", "room-hq3@trustworks.dk");
        assertTrue(RecruitmentSlackReactor.rescheduleDetailsChanged(room));

        Map<String, Object> duration = samePayload();
        duration.put("duration_minutes", 90);
        assertTrue(RecruitmentSlackReactor.rescheduleDetailsChanged(duration));
    }

    @Test
    void whoOnlyChange_isNotADetailChange() {
        assertFalse(RecruitmentSlackReactor.rescheduleDetailsChanged(samePayload()),
                "identical before/after details — only the interviewer list moved");
    }

    /** A payload whose before/after pairs are all identical. */
    private static Map<String, Object> samePayload() {
        Map<String, Object> payload = new HashMap<>();
        payload.put("previous_scheduled_at", "2026-08-20T10:00");
        payload.put("scheduled_at", "2026-08-20T10:00");
        payload.put("previous_location", "HQ meeting room 2");
        payload.put("location", "HQ meeting room 2");
        payload.put("previous_room_email", "room-hq2@trustworks.dk");
        payload.put("room_email", "room-hq2@trustworks.dk");
        payload.put("previous_duration_minutes", 60);
        payload.put("duration_minutes", 60);
        return payload;
    }
}
