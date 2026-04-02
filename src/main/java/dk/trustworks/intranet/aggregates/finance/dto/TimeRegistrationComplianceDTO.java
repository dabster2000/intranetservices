package dk.trustworks.intranet.aggregates.finance.dto;

import java.util.List;

/**
 * Time registration compliance for a single consultant over the last 6 months.
 * Measures whether hours are registered within 7 calendar days of the work day.
 */
public class TimeRegistrationComplianceDTO {
    private String userId;
    private List<MonthlyCompliance> months;
    private double averagePct;

    public static class MonthlyCompliance {
        private String monthKey;       // "202511"
        private String label;          // "Nov"
        private double compliancePct;
        private int totalDays;
        private int compliantDays;

        public MonthlyCompliance() {}

        public MonthlyCompliance(String monthKey, String label, double compliancePct, int totalDays, int compliantDays) {
            this.monthKey = monthKey;
            this.label = label;
            this.compliancePct = compliancePct;
            this.totalDays = totalDays;
            this.compliantDays = compliantDays;
        }

        public String getMonthKey() { return monthKey; }
        public void setMonthKey(String monthKey) { this.monthKey = monthKey; }

        public String getLabel() { return label; }
        public void setLabel(String label) { this.label = label; }

        public double getCompliancePct() { return compliancePct; }
        public void setCompliancePct(double compliancePct) { this.compliancePct = compliancePct; }

        public int getTotalDays() { return totalDays; }
        public void setTotalDays(int totalDays) { this.totalDays = totalDays; }

        public int getCompliantDays() { return compliantDays; }
        public void setCompliantDays(int compliantDays) { this.compliantDays = compliantDays; }
    }

    public TimeRegistrationComplianceDTO() {}

    public String getUserId() { return userId; }
    public void setUserId(String userId) { this.userId = userId; }

    public List<MonthlyCompliance> getMonths() { return months; }
    public void setMonths(List<MonthlyCompliance> months) { this.months = months; }

    public double getAveragePct() { return averagePct; }
    public void setAveragePct(double averagePct) { this.averagePct = averagePct; }
}
