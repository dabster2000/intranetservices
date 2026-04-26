package dk.trustworks.intranet.recruitmentservice.api.dto;

import dk.trustworks.intranet.recruitmentservice.domain.enums.ScorecardRecommendation;
import jakarta.validation.constraints.*;

public record SubmitScorecardRequest(
    @NotNull @Min(1) @Max(5) Byte practiceSkillFit,
    @NotNull @Min(1) @Max(5) Byte careerLevelFit,
    @NotNull @Min(1) @Max(5) Byte consultingCommunication,
    @NotNull @Min(1) @Max(5) Byte clientFacingMaturity,
    @NotNull @Min(1) @Max(5) Byte cultureValueFit,
    @NotNull @Min(1) @Max(5) Byte deliveryTrackPotential,
    @NotNull ScorecardRecommendation recommendation,
    String notes,
    String privateNotes,
    String concerns
) {}
