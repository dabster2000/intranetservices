package dk.trustworks.intranet.aggregates.jkdashboard.dto;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * Per-JK salary hours vs client hours for the scatter chart.
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class PerJkSalaryVsClientEntry {
    private String jkUuid;
    private String jkName;
    private double salaryHours;
    private double clientHours;
    private int hourlySalaryRate;
    private double totalSalaryCost;
}
