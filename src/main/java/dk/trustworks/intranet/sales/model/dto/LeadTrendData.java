package dk.trustworks.intranet.sales.model.dto;

/**
 * 3-month rolling trend data for sales lead KPI indicators.
 * Averages are computed over the 3 most recent completed calendar months.
 */
public record LeadTrendData(
        ActiveLeadTrend activeLeads,
        MonthlyLeadTrend newLeads,
        MonthlyLeadTrend wonLeads
) {
    /**
     * Active leads trend: average count and average total revenue across
     * the pipeline at each month-end snapshot.
     */
    public record ActiveLeadTrend(
            double avgCount,
            double avgRevenue
    ) {}

    /**
     * Monthly lead trend: average number of leads per month and average
     * total revenue of those leads per month.
     */
    public record MonthlyLeadTrend(
            double avgMonthlyCount,
            double avgMonthlyRevenue
    ) {}
}
