package dk.trustworks.intranet.recruitmentservice.dto;

import jakarta.validation.constraints.NotNull;

import java.time.LocalDate;

/**
 * Request body for
 * {@code PUT /recruitment/applications/{uuid}/expected-start-date} —
 * Airtable's <em>Ansættelsesdato</em>, typically set at OFFER; feeds
 * demand planning (spec Appendix A).
 */
public record ExpectedStartDateRequest(
        @NotNull(message = "expectedStartDate is required")
        LocalDate expectedStartDate
) {
}
