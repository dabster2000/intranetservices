package dk.trustworks.intranet.aggregates.jkdashboard.dto;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * Monthly salary cost aggregate for all JKs.
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class MonthlySalaryEntry {
    private String month;
    private double totalSalaryCost;
    private double totalSalaryHours;
    private int activeJkCount;
}
