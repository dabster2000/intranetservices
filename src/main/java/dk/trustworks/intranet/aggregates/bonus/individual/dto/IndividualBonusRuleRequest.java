package dk.trustworks.intranet.aggregates.bonus.individual.dto;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import dk.trustworks.intranet.aggregates.bonus.individual.model.Spec;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

import java.time.LocalDate;

/**
 * Create/update request for an individual bonus rule. Kept separate from the entity and the response
 * DTO. The {@code spec} arrives as a typed object and is serialised to JSON text on write.
 * {@code active} is nullable so it can be omitted (defaults to true on create).
 * Unknown properties are ignored so the BFF may send an extra client-side {@code uuid} (the server
 * assigns its own) without a 400.
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record IndividualBonusRuleRequest(
        @NotBlank String userUuid,
        @NotBlank String name,
        @NotNull LocalDate effectiveFrom,
        LocalDate effectiveTo,
        String replaces,
        Boolean active,
        @NotNull Spec spec
) {
}
