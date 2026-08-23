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
         * Whether the requesting user may close an <em>outcome</em> on this
         * application — hire, reject, withdraw, return-to-pool
         * ({@code RecruitmentVisibility.canDecideFinalOutcome}, decision 7 of
         * the 2026-08-23 access model). Always false when
         * {@link #viewerCanDecide} is false; narrower than it for an
         * {@code ASSISTANT_TEAMLEAD}, who moves stages but never closes an
         * outcome. The page hides Reject/Withdraw/Return-to-pool — and the
         * REJECT half of the interview decision — when this is false; the
         * backend enforces the same rule on those endpoints. Old frontends
         * that don't know the field simply keep gating everything on
         * {@code viewerCanDecide}, which is never wider than before.
         */
        boolean viewerCanDecideFinal,
        /**
         * Whether the requesting user <em>runs this hire</em> — the named
         * hiring owner, or a lead of the position's practice. Distinct from
         * {@link #viewerCanDecide} on purpose: it is what opens the read-only
         * offer/contract view, and a recruiter who may decide everywhere is
         * deliberately not in it.
         * <p>
         * Deliberately the same predicate as {@code canReadDossier}'s
         * hiring-owner branch ({@code RecruitmentVisibility
         * .isHiringOwnerForCandidate}), so the page cannot offer a contract
         * tab the backend then 404s. That includes the 2026-08-19 clause:
         * an application the viewer filed themselves does not count, so an
         * intake holder who attaches a candidate to their own position sees
         * no contract tab appear.
         */
        boolean viewerRunsHire
) {
}
