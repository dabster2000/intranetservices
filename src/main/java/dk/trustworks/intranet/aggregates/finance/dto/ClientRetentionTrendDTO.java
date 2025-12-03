package dk.trustworks.intranet.aggregates.finance.dto;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;

/**
 * Backend DTO for Client Retention & Growth Trend (Chart C)
 *
 * Contains quarterly retention metrics for the past 8 fiscal quarters.
 * Supports combination chart visualization (columns + line):
 * - New clients (green columns)
 * - Churned clients (red columns)
 * - Retention rate (blue line)
 *
 * Fiscal quarters follow Trustworks convention:
 * - Q1 FY = Jul-Sep
 * - Q2 FY = Oct-Dec
 * - Q3 FY = Jan-Mar
 * - Q4 FY = Apr-Jun
 *
 * Used by CxO Dashboard Client Portfolio Tab (Chart C: Client Retention & Growth Trend).
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class ClientRetentionTrendDTO {

    /**
     * List of 8 quarterly retention metrics, ordered chronologically
     * Index 0 = oldest quarter (8 quarters ago)
     * Index 7 = most recent quarter
     * Each quarter contains retention rate, new clients, churned clients, retained clients
     */
    private List<QuarterlyRetentionDTO> quarters;
}
