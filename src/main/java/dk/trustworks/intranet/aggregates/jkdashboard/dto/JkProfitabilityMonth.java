package dk.trustworks.intranet.aggregates.jkdashboard.dto;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * One month of profitability data for the JK team.
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class JkProfitabilityMonth {
    private String month;
    private double salaryCost;
    private double overheadCost;
    private double totalCost;
    private double actualRevenue;
    private double potentialRevenue;
    private double netProfitLoss;
    private double cumulativeProfitLoss;
}
