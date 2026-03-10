package dk.trustworks.intranet.aggregates.jkdashboard.dto;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * Details about a detected merge between a JK's hours and a regular consultant's invoice.
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class MergeDetail {
    private String regularConsultantUuid;
    private String regularConsultantName;
    private double regularRegisteredHours;
    private double regularInvoicedHours;
    private double surplusHours;
}
