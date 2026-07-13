package dk.trustworks.intranet.aggregates.bonus.individual.dto;

import dk.trustworks.intranet.aggregates.bonus.individual.model.Spec;

import java.time.LocalDate;
import java.time.LocalDateTime;

/**
 * Response DTO for an individual bonus rule. The {@code spec} is exposed as the parsed object (not
 * the raw JSON string) so clients get typed, validated content.
 */
public record IndividualBonusRuleDTO(
        String uuid,
        String userUuid,
        String name,
        LocalDate effectiveFrom,
        LocalDate effectiveTo,
        String replaces,
        boolean active,
        Spec spec,
        String createdBy,
        LocalDateTime createdAt,
        String modifiedBy,
        LocalDateTime updatedAt,
        long revision
) {
}
