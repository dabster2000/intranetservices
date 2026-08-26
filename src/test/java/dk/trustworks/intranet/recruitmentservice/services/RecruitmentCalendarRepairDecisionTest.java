package dk.trustworks.intranet.recruitmentservice.services;

import dk.trustworks.intranet.recruitmentservice.model.enums.RecruitmentInterviewStatus;
import dk.trustworks.intranet.recruitmentservice.services.RecruitmentCalendarRepairJob.RepairAction;
import org.junit.jupiter.api.Test;

import java.time.LocalDateTime;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * The calendar repair sweep's decision matrix (V533), pinned in the
 * DB-free tier that gates deploys. The matrix derives WHAT to repair
 * from the row's linkage columns — nothing is stored about the original
 * failure, so these rules are the whole contract between the marker
 * writers and the sweep.
 */
class RecruitmentCalendarRepairDecisionTest {

    private static final LocalDateTime NOW = LocalDateTime.of(2026, 8, 26, 10, 0);
    private static final LocalDateTime FUTURE = NOW.plusDays(1);
    private static final LocalDateTime PAST = NOW.minusHours(1);

    @Test
    void missingCandidateEvent_isTheProdCase_createCandidate() {
        // fdb0eb14's exact state: internal event stands, candidate event
        // lost to a 504, interview still ahead — the sweep must create the
        // candidate's own invitation, nothing else.
        assertEquals(RepairAction.CREATE_CANDIDATE, RecruitmentCalendarRepairJob.decide(
                RecruitmentInterviewStatus.SCHEDULED, "evt-int", null, true, FUTURE, NOW));
    }

    @Test
    void missingBothEvents_recreatesTheFullSplit() {
        assertEquals(RepairAction.CREATE_ALL, RecruitmentCalendarRepairJob.decide(
                RecruitmentInterviewStatus.SCHEDULED, null, null, true, FUTURE, NOW));
    }

    @Test
    void bothEventsPresent_candidateContentIsStale_patch() {
        assertEquals(RepairAction.PATCH_CANDIDATE, RecruitmentCalendarRepairJob.decide(
                RecruitmentInterviewStatus.SCHEDULED, "evt-int", "evt-cand", true, FUTURE, NOW));
    }

    @Test
    void emailRemovedSinceTheFailure_noInvitationIntended_dropMarker() {
        assertEquals(RepairAction.DROP_MARKER, RecruitmentCalendarRepairJob.decide(
                RecruitmentInterviewStatus.SCHEDULED, "evt-int", null, false, FUTURE, NOW));
    }

    @Test
    void cancelledWithEventsStanding_finishTheDeletes() {
        assertEquals(RepairAction.DELETE_EVENTS, RecruitmentCalendarRepairJob.decide(
                RecruitmentInterviewStatus.CANCELLED, "evt-int", "evt-cand", true, FUTURE, NOW));
        assertEquals(RepairAction.DELETE_EVENTS, RecruitmentCalendarRepairJob.decide(
                RecruitmentInterviewStatus.CANCELLED, null, "evt-cand", true, PAST, NOW),
                "a lone candidate event still gets cancelled — even after the time passed");
    }

    @Test
    void cancelledWithNothingLeft_dropMarker() {
        assertEquals(RepairAction.DROP_MARKER, RecruitmentCalendarRepairJob.decide(
                RecruitmentInterviewStatus.CANCELLED, null, null, true, FUTURE, NOW));
    }

    @Test
    void interviewTimePassed_neverRetryIntoThePast() {
        // Inviting someone to a meeting that already happened helps nobody
        // — the sweep stops and (when the invite never existed) alerts.
        assertEquals(RepairAction.ABANDON_PAST, RecruitmentCalendarRepairJob.decide(
                RecruitmentInterviewStatus.SCHEDULED, "evt-int", null, true, PAST, NOW));
        assertEquals(RepairAction.ABANDON_PAST, RecruitmentCalendarRepairJob.decide(
                RecruitmentInterviewStatus.HELD, "evt-int", null, true, NOW, NOW),
                "start time == now is already too late to invite anyone");
        assertEquals(RepairAction.ABANDON_PAST, RecruitmentCalendarRepairJob.decide(
                RecruitmentInterviewStatus.SCHEDULED, "evt-int", null, true, null, NOW),
                "no scheduled time reads as unrepairable, not as forever-future");
    }

    @Test
    void backoff_isTheOutboxCurve() {
        assertEquals(NOW.plusMinutes(2),
                RecruitmentCalendarRepairJob.nextAttemptAt(1, NOW));
        assertEquals(NOW.plusMinutes(4),
                RecruitmentCalendarRepairJob.nextAttemptAt(2, NOW));
        assertEquals(NOW.plusMinutes(60),
                RecruitmentCalendarRepairJob.nextAttemptAt(20, NOW),
                "capped at an hour, exactly like the scheduling outbox");
    }
}
