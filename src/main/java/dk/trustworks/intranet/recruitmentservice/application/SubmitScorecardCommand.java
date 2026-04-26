package dk.trustworks.intranet.recruitmentservice.application;

import dk.trustworks.intranet.recruitmentservice.domain.enums.ScorecardRecommendation;

/**
 * Command for {@link ScorecardService#submit}. All six dimensions are required
 * and must fall in the inclusive range 1..5; recommendation must be non-null.
 * Free-text fields are optional.
 */
public record SubmitScorecardCommand(
        Byte practiceSkillFit,
        Byte careerLevelFit,
        Byte consultingCommunication,
        Byte clientFacingMaturity,
        Byte cultureValueFit,
        Byte deliveryTrackPotential,
        ScorecardRecommendation recommendation,
        String notes,
        String privateNotes,
        String concerns
) {
    public void validate() {
        Byte[] dims = {practiceSkillFit, careerLevelFit, consultingCommunication,
                clientFacingMaturity, cultureValueFit, deliveryTrackPotential};
        for (Byte b : dims) {
            if (b == null || b < 1 || b > 5) {
                throw new IllegalArgumentException(
                        "all 6 dimensions required, each in range 1..5");
            }
        }
        if (recommendation == null) {
            throw new IllegalArgumentException("recommendation required");
        }
    }
}
