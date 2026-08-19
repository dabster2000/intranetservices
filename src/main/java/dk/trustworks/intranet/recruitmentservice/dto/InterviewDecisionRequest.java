package dk.trustworks.intranet.recruitmentservice.dto;

import dk.trustworks.intranet.recruitmentservice.model.enums.RecruitmentInterviewDecision;

/**
 * Body of {@code POST /recruitment/interviews/{uuid}/decision} (pipeline
 * sub-status feature): the owner's go/no-go for the round. Recording is
 * optional — moving the card directly still implies the decision.
 *
 * @param decision required; {@code ADVANCE} or {@code REJECT}
 */
public record InterviewDecisionRequest(RecruitmentInterviewDecision decision) {
}
