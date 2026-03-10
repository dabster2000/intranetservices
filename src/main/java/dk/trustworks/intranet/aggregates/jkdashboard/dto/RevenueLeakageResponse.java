package dk.trustworks.intranet.aggregates.jkdashboard.dto;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;

/**
 * Revenue leakage analysis with monthly breakdown and FY totals.
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class RevenueLeakageResponse {
    private List<RevenueLeakageMonth> months;
    private RevenueLeakageTotals fyTotals;
}
