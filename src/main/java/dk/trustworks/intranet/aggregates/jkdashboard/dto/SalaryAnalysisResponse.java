package dk.trustworks.intranet.aggregates.jkdashboard.dto;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;

/**
 * Salary analysis: monthly trend and per-JK salary vs client hours.
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class SalaryAnalysisResponse {
    private List<MonthlySalaryEntry> monthlySalary;
    private List<PerJkSalaryVsClientEntry> perJkSalaryVsClient;
}
