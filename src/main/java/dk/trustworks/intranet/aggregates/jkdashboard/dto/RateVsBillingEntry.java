package dk.trustworks.intranet.aggregates.jkdashboard.dto;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * Scatter data point: one JK-client combination's rate vs billing success.
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class RateVsBillingEntry {
    private String jkName;
    private String clientName;
    private double avgRate;
    private double billingPercent;
    private double totalHours;
}
