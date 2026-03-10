package dk.trustworks.intranet.aggregates.jkdashboard.dto;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * Per-JK profit and loss summary for the horizontal bar chart.
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class JkPnlEntry {
    private String jkUuid;
    private String jkName;
    private double salaryCost;
    private double overheadCost;
    private double totalCost;
    private double directRevenue;
    private double mergedRevenue;
    private double totalRevenue;
    private double netProfitLoss;
}
