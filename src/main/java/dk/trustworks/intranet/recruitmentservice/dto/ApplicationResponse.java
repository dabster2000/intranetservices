package dk.trustworks.intranet.recruitmentservice.dto;

import dk.trustworks.intranet.recruitmentservice.model.enums.RecruitmentApplicationTerminal;
import dk.trustworks.intranet.recruitmentservice.model.enums.RecruitmentHiringTrack;
import dk.trustworks.intranet.recruitmentservice.model.enums.RecruitmentRejectionReason;
import dk.trustworks.intranet.recruitmentservice.model.enums.RecruitmentStage;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;

/**
 * Wire shape of a recruitment application. Position facts (title, track,
 * stage set) are denormalized in so the UI can render a stage stepper and
 * a readable row without a second fetch — they are derived at read time
 * from the position row, never stored on the application.
 */
public record ApplicationResponse(
        String uuid,
        String candidateUuid,
        String positionUuid,
        String positionTitle,
        RecruitmentHiringTrack positionTrack,
        /**
         * The position's named hiring owner, denormalized like the other
         * position facts. The UI needs it to answer "is the viewer running
         * this hire?" — which is what opens the read-only offer/contract
         * view — without fetching every position just to find out.
         * {@code null} when the position has no named owner.
         */
        String positionHiringOwnerUuid,
        /** The position's ordered stage codes — drives stage steppers/pickers. */
        List<String> positionStageSet,
        RecruitmentStage stage,
        RecruitmentApplicationTerminal terminal,
        RecruitmentRejectionReason rejectionReasonCode,
        String assignedTeamUuid,
        LocalDate expectedStartDate,
        LocalDateTime stageEnteredAt,
        LocalDateTime createdAt,
        /**
         * Whether the requesting user may act on this application — move its
         * stage, reject, withdraw, schedule interviews, assign a team
         * ({@code RecruitmentVisibility.canDecideOnApplication}). The page
         * hides the controls when this is false rather than letting the user
         * click into a 403; the backend still enforces the same rule on every
         * one of those endpoints.
         */
        boolean viewerCanDecide,
        /**
         * Whether the requesting user <em>runs this hire</em> — the named
         * hiring owner, or a lead of the position's practice. Distinct from
         * {@link #viewerCanDecide} on purpose: it is what opens the read-only
         * offer/contract view, and a recruiter who may decide everywhere is
         * deliberately not in it.
         */
        boolean viewerRunsHire
) {
}
