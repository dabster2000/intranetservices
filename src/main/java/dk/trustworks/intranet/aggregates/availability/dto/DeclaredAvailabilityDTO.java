package dk.trustworks.intranet.aggregates.availability.dto;

import dk.trustworks.intranet.aggregates.availability.model.UserDeclaredAvailability;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;

/**
 * One declared day as the individual view sees it.
 *
 * <p>{@code note} is free text a junior may put health details into ("sygemeldt,
 * psykolog"). It is returned <em>only</em> here — to the person themselves and the
 * individual (KPC) view. Team-level, Junior Talent and account-manager reads use their
 * own projections and never carry it (spec §4.1.6).
 */
public record DeclaredAvailabilityDTO(
        String uuid,
        String useruuid,
        LocalDate day,
        BigDecimal hours,
        String source,
        String note,
        LocalDateTime updatedAt,
        String updatedBy
) {
    public static DeclaredAvailabilityDTO from(UserDeclaredAvailability row) {
        return new DeclaredAvailabilityDTO(
                row.getUuid(),
                row.getUseruuid(),
                row.getDay(),
                row.getHours(),
                row.getSource() == null ? null : row.getSource().name(),
                row.getNote(),
                row.getUpdatedAt(),
                row.getUpdatedBy()
        );
    }
}
