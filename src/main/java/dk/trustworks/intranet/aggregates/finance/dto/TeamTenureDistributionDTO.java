package dk.trustworks.intranet.aggregates.finance.dto;

import java.util.List;

/**
 * Tenure distribution bucket for a team.
 * Buckets: {@code <1y}, {@code 1-2y}, {@code 2-3y}, {@code 3-5y}, {@code 5y+}.
 */
public record TeamTenureDistributionDTO(
        /** Bucket label (e.g., "<1y", "1-2y", "2-3y", "3-5y", "5y+") */
        String bucket,
        /** Sort order for display (0 = shortest tenure) */
        int sortOrder,
        /** Number of team members in this bucket */
        int count,
        /** UUIDs of team members in this bucket */
        List<String> memberUuids
) {}
