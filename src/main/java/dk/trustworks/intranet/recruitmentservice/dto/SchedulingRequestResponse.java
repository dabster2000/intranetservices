package dk.trustworks.intranet.recruitmentservice.dto;

import dk.trustworks.intranet.recruitmentservice.model.enums.AvailabilityConstraintType;
import dk.trustworks.intranet.recruitmentservice.model.enums.CalendarHoldOwnerKind;
import dk.trustworks.intranet.recruitmentservice.model.enums.CalendarHoldStatus;
import dk.trustworks.intranet.recruitmentservice.model.enums.EvidenceConfirmationStatus;
import dk.trustworks.intranet.recruitmentservice.model.enums.EvidenceSourceType;
import dk.trustworks.intranet.recruitmentservice.model.enums.OptionBatchStatus;
import dk.trustworks.intranet.recruitmentservice.model.enums.ProposedSlotStatus;
import dk.trustworks.intranet.recruitmentservice.model.enums.RecruitmentInterviewKind;
import dk.trustworks.intranet.recruitmentservice.model.enums.SchedulingRequestStatus;
import dk.trustworks.intranet.recruitmentservice.model.enums.SlotApprovalStatus;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.util.List;

/**
 * The FE panel aggregate of {@code GET /recruitment/scheduling-requests/{uuid}}
 * (plan §8.5): the request, its slots with approvals and hold health, the
 * option batch and the cleanup-warning count ({@code failedOutboxActions}
 * &gt; 0 ⇒ an external write is dead-lettered and needs a human,
 * spec §21.5). No token material ever appears here.
 */
public record SchedulingRequestResponse(
        String uuid,
        String applicationUuid,
        String recruiterUuid,
        SchedulingRequestStatus status,
        RecruitmentInterviewKind kind,
        Integer round,
        int durationMinutes,
        boolean onlineMeeting,
        boolean requireRoom,
        String location,
        List<String> interviewerUuids,
        List<String> optionalInterviewerUuids,
        int requestedOptions,
        LocalDate windowStart,
        LocalDate windowEnd,
        LocalTime permittedStart,
        LocalTime permittedEnd,
        int minSeparationHours,
        boolean differentDays,
        LocalDateTime candidateDeadline,
        LocalDateTime automationDeadline,
        boolean reviewRequired,
        boolean manualCandidateDelivery,
        LocalDateTime optionsApprovedAt,
        String handbackReason,
        List<SlotView> slots,
        BatchView activeBatch,
        long failedOutboxActions,
        List<EvidenceView> evidence,
        LocalDateTime createdAt,
        LocalDateTime updatedAt
) {

    /** One slot with its approval and hold state. */
    public record SlotView(
            String uuid,
            int optionNo,
            LocalDateTime slotStart,
            LocalDateTime slotEnd,
            String roomEmail,
            String roomName,
            ProposedSlotStatus status,
            String rejectReason,
            LocalDateTime expiresAt,
            List<ApprovalView> approvals,
            List<HoldView> holds
    ) {
    }

    /** One required interviewer's answer state. */
    public record ApprovalView(
            String uuid,
            String userUuid,
            SlotApprovalStatus status,
            LocalDateTime respondedAt
    ) {
    }

    /** One hold's health; {@code inCalendar} = the Graph event exists
     * (as far as creation/reconciliation know). */
    public record HoldView(
            String uuid,
            CalendarHoldOwnerKind ownerKind,
            String userUuid,
            CalendarHoldStatus status,
            boolean inCalendar,
            LocalDateTime lastVerifiedAt
    ) {
    }

    /** The ACTIVE option batch, when one exists (Phase 11). */
    public record BatchView(
            String uuid,
            LocalDateTime sentAt,
            LocalDateTime expiresAt,
            OptionBatchStatus status
    ) {
    }

    /**
     * One availability-evidence row for the panel (Phase 14): the
     * confirmed inputs shaping the search, plus the REJECTED/UNKNOWN
     * rows the recruiter reviews manually (the plan §14 list) —
     * structure only, the source text stays in event pii.
     */
    public record EvidenceView(
            String uuid,
            String userUuid,
            EvidenceSourceType sourceType,
            String intent,
            EvidenceConfirmationStatus status,
            String language,
            LocalDate coveredFrom,
            LocalDate coveredTo,
            String confidence,
            LocalDateTime expiresAt,
            LocalDateTime createdAt,
            List<ConstraintView> constraints
    ) {
    }

    /** One normalized interval of one evidence row. */
    public record ConstraintView(
            AvailabilityConstraintType type,
            LocalDateTime startAt,
            LocalDateTime endAt
    ) {
    }
}
