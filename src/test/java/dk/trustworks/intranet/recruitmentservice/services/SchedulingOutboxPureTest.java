package dk.trustworks.intranet.recruitmentservice.services;

import dk.trustworks.intranet.recruitmentservice.model.RecruitmentSlotApproval;
import dk.trustworks.intranet.recruitmentservice.model.enums.ProposedSlotStatus;
import dk.trustworks.intranet.recruitmentservice.model.enums.SchedulingOutboxAction;
import dk.trustworks.intranet.recruitmentservice.model.enums.SchedulingRequestStatus;
import dk.trustworks.intranet.recruitmentservice.model.enums.SlotApprovalStatus;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;

/**
 * The pure Method B orchestration rules (plan §8.3): outbox idempotency
 * keys, the retry backoff schedule, the slot-status recompute and the
 * pipeline-status derivation. DB-free tier.
 */
class SchedulingOutboxPureTest {

    // ---- Idempotency keys -------------------------------------------------

    @Test
    void idempotencyKey_pinsTheActionInstance() {
        String key = SchedulingOutboxService.idempotencyKey(
                "req-1", "slot-1", SchedulingOutboxAction.CREATE_HOLD, "hold-1");
        assertEquals("req-1:slot-1:CREATE_HOLD:hold-1", key);
        // A different qualifier is a different intended action.
        assertNotEquals(key, SchedulingOutboxService.idempotencyKey(
                "req-1", "slot-1", SchedulingOutboxAction.CREATE_HOLD, "hold-2"));
    }

    @Test
    void idempotencyKey_toleratesRequestLevelActions() {
        assertEquals("req-1:-:SEND_RECRUITER_DM:HANDBACK:3",
                SchedulingOutboxService.idempotencyKey(
                        "req-1", null, SchedulingOutboxAction.SEND_RECRUITER_DM,
                        "HANDBACK:3"));
    }

    // ---- Backoff ----------------------------------------------------------

    @Test
    void backoff_isExponentialAndCapped() {
        assertEquals(2, SchedulingOutboxService.backoffMinutes(1));
        assertEquals(4, SchedulingOutboxService.backoffMinutes(2));
        assertEquals(8, SchedulingOutboxService.backoffMinutes(3));
        assertEquals(16, SchedulingOutboxService.backoffMinutes(4));
        assertEquals(32, SchedulingOutboxService.backoffMinutes(5));
        assertEquals(60, SchedulingOutboxService.backoffMinutes(6));
        assertEquals(60, SchedulingOutboxService.backoffMinutes(7));
        assertEquals(60, SchedulingOutboxService.backoffMinutes(100));
    }

    // ---- Slot status recompute -------------------------------------------

    @Test
    void slotStatus_followsTheApprovalSet() {
        assertEquals(ProposedSlotStatus.PROPOSED,
                RecruitmentSchedulingService.recomputeSlotStatus(List.of(
                        approval(SlotApprovalStatus.PENDING),
                        approval(SlotApprovalStatus.PENDING))));
        assertEquals(ProposedSlotStatus.PARTIALLY_APPROVED,
                RecruitmentSchedulingService.recomputeSlotStatus(List.of(
                        approval(SlotApprovalStatus.APPROVED),
                        approval(SlotApprovalStatus.PENDING))));
        assertEquals(ProposedSlotStatus.APPROVED,
                RecruitmentSchedulingService.recomputeSlotStatus(List.of(
                        approval(SlotApprovalStatus.APPROVED),
                        approval(SlotApprovalStatus.APPROVED))));
        // A single decline rejects the slot no matter what else exists.
        assertEquals(ProposedSlotStatus.REJECTED,
                RecruitmentSchedulingService.recomputeSlotStatus(List.of(
                        approval(SlotApprovalStatus.APPROVED),
                        approval(SlotApprovalStatus.DECLINED))));
    }

    // ---- Pipeline status derivation --------------------------------------

    @Test
    void pipelineStatus_derivesFromTheSlotCensus() {
        assertEquals(SchedulingRequestStatus.SEARCHING,
                RecruitmentSchedulingOrchestrator.desiredPipelineStatus(0, 0, 3));
        assertEquals(SchedulingRequestStatus.WAITING_FOR_INTERVIEWERS,
                RecruitmentSchedulingOrchestrator.desiredPipelineStatus(0, 2, 3));
        assertEquals(SchedulingRequestStatus.HOLDING_OPTIONS,
                RecruitmentSchedulingOrchestrator.desiredPipelineStatus(1, 3, 3));
        assertEquals(SchedulingRequestStatus.READY_FOR_CANDIDATE,
                RecruitmentSchedulingOrchestrator.desiredPipelineStatus(3, 3, 3));
        // A single-option request is secured by one hold.
        assertEquals(SchedulingRequestStatus.READY_FOR_CANDIDATE,
                RecruitmentSchedulingOrchestrator.desiredPipelineStatus(1, 1, 1));
    }

    private static RecruitmentSlotApproval approval(SlotApprovalStatus status) {
        RecruitmentSlotApproval approval = new RecruitmentSlotApproval();
        approval.setStatus(status);
        return approval;
    }
}
