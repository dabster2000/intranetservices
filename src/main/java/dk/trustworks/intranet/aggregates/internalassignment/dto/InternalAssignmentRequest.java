package dk.trustworks.intranet.aggregates.internalassignment.dto;

import java.math.BigDecimal;
import java.time.LocalDate;

/**
 * Body of {@code POST /users/{useruuid}/internal-assignments} and
 * {@code PUT /internal-assignments/{uuid}}.
 *
 * <p>Sizing (D9): exactly one of {@code hoursPerWeek} or {@code totalHours} is given. A total
 * is converted to the canonical {@code hours_per_week} ({@code total ÷ weekdays-in-period × 5})
 * and kept as {@code estimated_total_hours} for display.
 *
 * <p>The assignee is the path (create) or the row (update), never the body; status and
 * approval fields are never accepted here — approval is its own endpoint.
 */
public record InternalAssignmentRequest(
        String title,
        String sponsorUseruuid,
        LocalDate activeFrom,
        LocalDate activeTo,
        BigDecimal hoursPerWeek,
        BigDecimal totalHours,
        Boolean strategic,
        String notes
) {
}
