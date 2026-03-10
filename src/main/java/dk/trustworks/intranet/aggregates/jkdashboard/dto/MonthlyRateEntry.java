package dk.trustworks.intranet.aggregates.jkdashboard.dto;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * Monthly rate statistics for JK billing rate analysis.
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class MonthlyRateEntry {
    private String month;
    private double avgRate;
    private double minRate;
    private double maxRate;
    private int jkCount;
    private double totalHours;
}
