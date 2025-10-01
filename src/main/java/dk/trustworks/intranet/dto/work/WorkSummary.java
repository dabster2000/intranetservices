package dk.trustworks.intranet.dto.work;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import org.eclipse.microprofile.openapi.annotations.media.Schema;

import java.time.LocalDate;

/**
 * Aggregated summary statistics for work data within a specified period.
 * Provides high-level metrics without returning individual work records.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@Schema(description = "Aggregated work statistics for a specified period")
public class WorkSummary {

    @Schema(description = "Number of unique users who have logged work",
            example = "42",
            required = true)
    private Integer uniqueUsers;

    @Schema(description = "Number of unique tasks worked on",
            example = "156",
            required = true)
    private Integer uniqueTasks;

    @Schema(description = "Number of unique projects with work entries",
            example = "23",
            required = true)
    private Integer uniqueProjects;

    @Schema(description = "Total hours worked across all entries",
            example = "3567.5",
            required = true)
    private Double totalHours;

    @Schema(description = "Total revenue generated (sum of hours * rate)",
            example = "4280400.00",
            required = true)
    private Double totalRevenue;

    @Schema(description = "Total number of work entries",
            example = "1892",
            required = true)
    private Long totalEntries;

    @Schema(description = "Start date of the period (inclusive)",
            example = "2024-01-01",
            required = true)
    private LocalDate fromDate;

    @Schema(description = "End date of the period (exclusive)",
            example = "2024-02-01",
            required = true)
    private LocalDate toDate;

    /**
     * Calculate average hours per user.
     * @return Average hours or 0 if no users
     */
    @Schema(description = "Average hours worked per user",
            example = "84.94")
    public double getAverageHoursPerUser() {
        return uniqueUsers != null && uniqueUsers > 0
                ? (totalHours != null ? totalHours : 0.0) / uniqueUsers
                : 0.0;
    }

    /**
     * Calculate average revenue per user.
     * @return Average revenue or 0 if no users
     */
    @Schema(description = "Average revenue generated per user",
            example = "101914.29")
    public double getAverageRevenuePerUser() {
        return uniqueUsers != null && uniqueUsers > 0
                ? (totalRevenue != null ? totalRevenue : 0.0) / uniqueUsers
                : 0.0;
    }

    /**
     * Calculate average hourly rate.
     * @return Average rate or 0 if no hours
     */
    @Schema(description = "Average hourly rate across all entries",
            example = "1200.00")
    public double getAverageHourlyRate() {
        return totalHours != null && totalHours > 0
                ? (totalRevenue != null ? totalRevenue : 0.0) / totalHours
                : 0.0;
    }

    /**
     * Get the number of days in the period.
     * @return Number of days between fromDate and toDate
     */
    @Schema(description = "Number of days in the period",
            example = "31")
    public long getPeriodDays() {
        if (fromDate == null || toDate == null) {
            return 0;
        }
        return java.time.temporal.ChronoUnit.DAYS.between(fromDate, toDate);
    }
}