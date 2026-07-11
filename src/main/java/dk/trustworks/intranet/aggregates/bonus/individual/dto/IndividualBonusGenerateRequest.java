package dk.trustworks.intranet.aggregates.bonus.individual.dto;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/**
 * Unsaved AI-authoring request. Contract text and optional hints are bounded before they can
 * reach the external model; the employee UUID remains server-side context and is not sent to it.
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record IndividualBonusGenerateRequest(
        @NotBlank String userUuid,
        @NotBlank @Size(max = 20_000) String text,
        @Size(max = 2_000) String hints
) {
}
