package dk.trustworks.intranet.aggregates.finance.dto;

import java.util.List;

/**
 * Career level distribution for a team, grouped by career track.
 * Used by the People tab of the Team Lead Dashboard.
 *
 * <p>Each entry represents one career level with its count and the career track it belongs to.
 */
public record TeamCareerDistributionDTO(
        /** Career track (e.g., DELIVERY, ADVISORY, LEADERSHIP, CLIENT_ENGAGEMENT, PARTNER, C_LEVEL) */
        String careerTrack,
        /** Career level within the track (e.g., CONSULTANT, SENIOR_CONSULTANT) */
        String careerLevel,
        /** Number of team members at this career level */
        int count,
        /** UUIDs of team members at this level */
        List<String> memberUuids
) {}
