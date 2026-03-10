package dk.trustworks.intranet.aggregates.jkdashboard.dto;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * Revenue leakage breakdown for one month.
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class RevenueLeakageMonth {
    private String month;
    private double directlyBilled;
    private double merged;
    private double trulyLost;
    private double uncertain;
    private double totalPotential;
}
