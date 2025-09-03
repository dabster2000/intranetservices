package dk.trustworks.intranet.apigateway.dto;

import dk.trustworks.intranet.domain.user.entity.User;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;

/** Day-based monthly foundation per user for a fiscal year (Jul -> Jun). */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class EmployeeBonusBasisDTO {
    private User user;
    private int year; // fiscal start year (July of this calendar year)
    private List<MonthBasis> months; // 12 entries, covering Jul..Jun in calendar order (Jul..Dec of 'year', Jan..Jun of 'year'+1)

    @Data
    @NoArgsConstructor
    @AllArgsConstructor
    public static class MonthBasis {
        private int year;                 // calendar year of the month
        private int month;                // 1..12
        private double eligibleShare;     // 0..1 = eligibleDays / daysInMonth
        private double avgSalary;         // sum(dailySalary)/daysInMonth
        private double weightedAvgSalary; // sum(dailySalary * eligibleFlag)/daysInMonth
    }
}
