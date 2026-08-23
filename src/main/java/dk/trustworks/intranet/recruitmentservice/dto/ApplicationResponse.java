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
         * position facts. The UI needs it as workflow context to answer "who
         * is the named owner?" without fetching every position just to find
         * out. It is not a dossier grant; only
         * {@link #viewerCanReadDossier} may open the offer/contract view.
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
         * Whether the requesting user may read the candidate's offer and
         * contract dossier. This is the authoritative capability for showing
         * the read-only Offer & Contract surface and is intentionally derived
         * from {@code RecruitmentVisibility.canReadDossier}, the same
         * predicate enforced by the dossier resource. It is false for an
         * assistant-only viewer even when {@link #viewerRunsHire} is true;
         * TEAMLEAD/HR/ADMIN combinations retain the access their broader
         * standing grants.
         */
        boolean viewerCanReadDossier,
        /**
         * Whether the requesting user <em>runs this hire</em> — the named
         * hiring owner; partner-track ownership also requires membership in
         * that position's circle. Distinct from {@link #viewerCanDecide} on
         * purpose: a recruiter who may decide everywhere is deliberately not
         * in it.
         * <p>
         * This remains useful as workflow context, but it is not an access
         * grant: {@link #viewerCanReadDossier} is the only dossier-rendering
         * capability. In particular, the assistant-only exclusion makes it
         * possible for this field to be true while dossier readability is
         * false. The 2026-08-19 self-attach clause still applies here: an
         * application the viewer filed themselves does not count.
         */
        boolean viewerRunsHire
) {
}
