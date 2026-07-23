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
        List<String> stageSet
) {
}
