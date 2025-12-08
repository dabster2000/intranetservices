package dk.trustworks.intranet.aggregates.finance.dto;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDate;

/**
 * Client Engagement DTO - Single client row with engagement metrics.
 * Used in the Engagement by Company chart and list.
 *
 * Contains engagement duration and activity metrics for a single client including:
 * - Engagement date range (first/last engagement)
 * - Duration in months
 * - Activity indicators (total hours, unique consultants)
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class ClientEngagementDTO {

    /**
     * Unique client identifier (UUID)
     */
    private String clientId;

    /**
     * Client display name
     */
    private String clientName;

    /**
     * Client industry segment (e.g., "PUBLIC", "HEALTH", "FINANCIAL")
     */
    private String segment;

    /**
     * Whether the client is currently active in the system.
     * True = active customer, False = inactive/churned
     */
    private boolean active;

    /**
     * Date of first engagement (MIN of work.registered).
     * Represents when the client relationship began.
     */
    private LocalDate firstEngagementDate;

    /**
     * Date of last engagement (MAX of work.registered).
     * Represents the most recent activity with the client.
     */
    private LocalDate lastEngagementDate;

    /**
     * Total engagement duration in months.
     * Calculated as: DATEDIFF(lastEngagementDate, firstEngagementDate) / 30.44
     */
    private double engagementMonths;

    /**
     * Total hours worked for this client across all time.
     * Sum of work.workduration for all work records.
     */
    private double totalHours;

    /**
     * Count of unique consultants who have worked with this client.
     * COUNT(DISTINCT work.useruuid)
     */
    private int uniqueConsultants;
}
