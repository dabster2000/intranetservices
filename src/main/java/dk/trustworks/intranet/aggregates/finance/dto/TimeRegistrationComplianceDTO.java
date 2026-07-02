package dk.trustworks.intranet.aggregates.finance.dto;

import java.util.List;

/**
 * Time registration compliance for a single consultant over the last 6 <em>complete</em> months.
 * A work-day is "compliant" if the entry was created within 7 calendar days of the work date,
 * measured from the immutable {@code work.created_at} (see V387 migration) — NOT from
 * {@code updated_at}, which payout/edit UPDATEs rewrite (audit finding C8).
 *
 * <p>Only rows with a known {@code created_at} are "measurable". Rows created before V387 whose
 * timestamp was already clobbered (invoiced work) are unmeasurable and are excluded from the
 * numerator <em>and</em> denominator, so they can never render a diligent consultant red.
 * {@code coveragePct} exposes how much of the month is actually measurable; the metric self-heals
 * to full coverage as post-V387 rows accumulate.
 */
public class TimeRegistrationComplianceDTO {
    private String userId;
    private List<MonthlyCompliance> months;
    /** Day-weighted mean over measurable days (sum(compliant)/sum(measured)); null if nothing measurable. */
    private Double averagePct;
    /** Overall share of worked days that were measurable across the window (measured/registered). */
    private double coveragePct;

    public static class MonthlyCompliance {
        private String monthKey;        // "202511"
        private String label;           // "Nov"
        /** compliantDays / measuredDays * 100; null when no measurable days (renders "no data", not red). */
        private Double compliancePct;
        /** measuredDays / registeredDays * 100 — how much of the month can actually be judged. */
        private double coveragePct;
        /** Distinct work-days the consultant registered anything in the month (the coverage denominator). */
        private int registeredDays;
        /** Distinct work-days with a known created_at (the compliance denominator). */
        private int measuredDays;
        /** Distinct measurable work-days registered within 7 calendar days. */
        private int compliantDays;

        public MonthlyCompliance() {}

        public MonthlyCompliance(String monthKey, String label, Double compliancePct, double coveragePct,
                                 int registeredDays, int measuredDays, int compliantDays) {
            this.monthKey = monthKey;
            this.label = label;
            this.compliancePct = compliancePct;
            this.coveragePct = coveragePct;
            this.registeredDays = registeredDays;
            this.measuredDays = measuredDays;
            this.compliantDays = compliantDays;
        }

        public String getMonthKey() { return monthKey; }
        public void setMonthKey(String monthKey) { this.monthKey = monthKey; }

        public String getLabel() { return label; }
        public void setLabel(String label) { this.label = label; }

        public Double getCompliancePct() { return compliancePct; }
        public void setCompliancePct(Double compliancePct) { this.compliancePct = compliancePct; }

        public double getCoveragePct() { return coveragePct; }
        public void setCoveragePct(double coveragePct) { this.coveragePct = coveragePct; }

        public int getRegisteredDays() { return registeredDays; }
        public void setRegisteredDays(int registeredDays) { this.registeredDays = registeredDays; }

        public int getMeasuredDays() { return measuredDays; }
        public void setMeasuredDays(int measuredDays) { this.measuredDays = measuredDays; }

        public int getCompliantDays() { return compliantDays; }
        public void setCompliantDays(int compliantDays) { this.compliantDays = compliantDays; }
    }

    public TimeRegistrationComplianceDTO() {}

    public String getUserId() { return userId; }
    public void setUserId(String userId) { this.userId = userId; }

    public List<MonthlyCompliance> getMonths() { return months; }
    public void setMonths(List<MonthlyCompliance> months) { this.months = months; }

    public Double getAveragePct() { return averagePct; }
    public void setAveragePct(Double averagePct) { this.averagePct = averagePct; }

    public double getCoveragePct() { return coveragePct; }
    public void setCoveragePct(double coveragePct) { this.coveragePct = coveragePct; }
}
