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
 * {@code ruleUuid} is accepted into the transport type solely so controlled monthly CREATE requests
 * can reject client-assigned identities explicitly instead of silently ignoring them.
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record IndividualBonusRuleRequest(
        @NotBlank String userUuid,
        @NotBlank String name,
        @NotNull LocalDate effectiveFrom,
        LocalDate effectiveTo,
        String replaces,
        Boolean active,
        @NotNull Spec spec,
        Long revision,
        String ruleUuid
) {
    /** Source-compatible constructor retained for legacy service/tests and non-monthly callers. */
    public IndividualBonusRuleRequest(String userUuid, String name, LocalDate effectiveFrom,
                                      LocalDate effectiveTo, String replaces, Boolean active, Spec spec) {
        this(userUuid, name, effectiveFrom, effectiveTo, replaces, active, spec, null, null);
    }

    /** Source-compatible constructor for callers that already supply optimistic revision. */
    public IndividualBonusRuleRequest(String userUuid, String name, LocalDate effectiveFrom,
                                      LocalDate effectiveTo, String replaces, Boolean active, Spec spec,
                                      Long revision) {
        this(userUuid, name, effectiveFrom, effectiveTo, replaces, active, spec, revision, null);
    }
}
