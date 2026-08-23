package dk.trustworks.intranet.recruitmentservice.dto;

import dk.trustworks.intranet.recruitmentservice.model.enums.RecruitmentDemandRag;
import dk.trustworks.intranet.recruitmentservice.model.enums.RecruitmentHiringTrack;
import dk.trustworks.intranet.recruitmentservice.model.enums.RecruitmentPositionStatus;

import java.util.List;

/**
 * The position block of {@link PositionBoardResponse} — everything the
 * pipeline page needs to render the board header and its columns without
 * a second fetch (P7 contract {@code IPositionBoard.position}).
 * <p>
 * {@code name} carries the position's title (the contract's field name);
 * practice facts are the registry-derived {@code @Formula} values from
 * {@code RecruitmentPosition}. {@code stageSet} is the ordered stage
 * codes the {@code columns} array follows one-to-one — including
 * {@code HIRED}, which renders as a normal column.
 */
public record BoardPositionSummary(
        String uuid,
        String name,
        RecruitmentHiringTrack hiringTrack,
        String practiceUuid,
        String practiceName,
        String practiceCode,
        Boolean practiceActive,
        String teamUuid,
        String hiringOwnerUuid,
        RecruitmentPositionStatus status,
        RecruitmentDemandRag demandRag,
        List<String> stageSet,
        /**
         * Whether the requesting user may move cards on this board
         * ({@code RecruitmentVisibility.canDecideOnApplication}). A team lead
         * reads every non-partner board but may only act on their own
         * practice's, so the page needs this to render the rest read-only
         * rather than letting a drag fail with a 403.
         */
        boolean viewerCanDecide,
        /**
         * Whether the requesting user may close an outcome from this board —
         * reject, withdraw, return-to-pool
         * ({@code RecruitmentVisibility.canDecideFinalOutcome}, decision 7 of
         * the 2026-08-23 access model). Always false when
         * {@link #viewerCanDecide} is false; narrower than it for an
         * {@code ASSISTANT_TEAMLEAD}, who moves cards but never closes an
         * outcome. The board withholds the terminal card actions (and the
         * REJECT half of record-decision) when this is false.
         */
        boolean viewerCanDecideFinal
) {
}
