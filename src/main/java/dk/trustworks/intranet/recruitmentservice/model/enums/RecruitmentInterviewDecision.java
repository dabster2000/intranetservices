package dk.trustworks.intranet.recruitmentservice.model.enums;

/**
 * The owner's recorded go/no-go for one interview round (pipeline
 * sub-status feature): {@code ADVANCE} = invite to the next stage,
 * {@code REJECT} = the process ends here. Recording is optional — moving
 * or rejecting the application directly still implies the decision, as it
 * always did. While a decision is pending on the current round the board
 * shows the "Inform candidate" sub-status; the stage move (or terminal)
 * that follows consumes it.
 */
public enum RecruitmentInterviewDecision {
    ADVANCE,
    REJECT
}
