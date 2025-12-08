package dk.trustworks.intranet.aggregates.finance.dto;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;

/**
 * Backend DTO for Engagement by Company chart.
 *
 * Contains a list of clients with their engagement durations, sorted by duration descending.
 * Includes portfolio average for reference line display on the chart.
 *
 * Used by CxO Dashboard Client Portfolio Tab - Engagement by Company chart.
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class EngagementByCompanyDTO {

    /**
     * List of clients with engagement metrics.
     * Sorted by engagementMonths descending (longest engagements first).
     * Limited to top N clients as specified by the limit parameter.
     */
    private List<ClientEngagementDTO> clients;

    /**
     * Portfolio-wide average engagement length in months.
     * Used to display a reference line on the chart.
     */
    private double portfolioAvgMonths;
}
