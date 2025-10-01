package dk.trustworks.intranet.dto.work;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import org.eclipse.microprofile.openapi.annotations.media.Schema;

import java.time.LocalDate;

/**
 * Lightweight work data transfer object containing only essential fields.
 * Used for performance-optimized queries that don't require full work entity data.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@Schema(description = "Lightweight work record containing only essential fields for optimal performance")
public class LightweightWork {

    @Schema(description = "Unique identifier for the work entry",
            example = "550e8400-e29b-41d4-a716-446655440000",
            required = true)
    private String uuid;

    @Schema(description = "UUID of the user who performed the work",
            example = "123e4567-e89b-12d3-a456-426614174000",
            required = true)
    private String useruuid;

    @Schema(description = "Date when the work was registered",
            example = "2024-01-15",
            required = true)
    private LocalDate registered;

    @Schema(description = "Duration of work in hours",
            example = "7.5",
            required = true)
    private Double workduration;

    @Schema(description = "UUID of the task associated with this work",
            example = "987e6543-e21b-12d3-a456-426614174000",
            required = true)
    private String taskuuid;

    @Schema(description = "Indicates if the work is billable to client",
            example = "true",
            required = true)
    private Boolean billable;

    @Schema(description = "Hourly rate for this work entry",
            example = "1200.00")
    private Double rate;

    @Schema(description = "UUID of the project this work belongs to",
            example = "abc12345-f67g-89h0-ijkl-123456789012")
    private String projectuuid;

    /**
     * Calculate the revenue for this work entry.
     * @return The revenue (workduration * rate) or 0 if either is null
     */
    @Schema(hidden = true)
    public double getRevenue() {
        if (workduration == null || rate == null) {
            return 0.0;
        }
        return workduration * rate;
    }

    /**
     * Check if this work entry generates revenue.
     * @return true if both rate and duration are positive
     */
    @Schema(hidden = true)
    public boolean isRevenue() {
        return workduration != null && workduration > 0
                && rate != null && rate > 0;
    }
}