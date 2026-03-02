package dk.trustworks.intranet.aggregates.delivery.usecases;

import dk.trustworks.intranet.aggregates.delivery.dto.BreakEvenCareerLevelDTO;
import dk.trustworks.intranet.aggregates.delivery.dto.BreakEvenCompanyWideDTO;
import dk.trustworks.intranet.aggregates.delivery.dto.BreakEvenUtilizationDTO;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.persistence.EntityManager;
import lombok.extern.jbosslog.JBossLog;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Use case for computing break-even utilization KPIs from the fact_minimum_viable_rate view.
 *
 * Break-even utilization answers the question: "At our current billing rates and cost
 * structure, what utilization rate must consultants achieve to cover costs?"
 *
 * Formula: Break-Even = Total Monthly Cost / (Net Available Hours x Avg Billing Rate)
 * With margin: Break-Even at X% = Total Monthly Cost / ((1 - X%) x Net Available Hours x Avg Rate)
 */
@JBossLog
@ApplicationScoped
public class BreakEvenUtilizationUseCase {

    /**
     * Maps raw career_level strings from the DB to human-readable display labels.
     */
    private static final Map<String, String> CAREER_LEVEL_LABELS = Map.ofEntries(
            Map.entry("JUNIOR", "Junior"),
            Map.entry("INTERMEDIATE", "Intermediate"),
            Map.entry("SENIOR", "Senior Consultant"),
            Map.entry("LEAD", "Lead Consultant"),
            Map.entry("MANAGER", "Manager"),
            Map.entry("SENIOR_MANAGER", "Senior Manager"),
            Map.entry("PARTNER", "Partner"),
            Map.entry("MANAGING_PARTNER", "Managing Partner"),
            Map.entry("STUDENT", "Student"),
            Map.entry("STAFF", "Staff")
    );

    @Inject
    EntityManager em;

    /**
     * Retrieves break-even utilization data for all career levels and computes
     * company-wide weighted aggregates.
     *
     * Per-career-level rows come directly from fact_minimum_viable_rate.
     * Company-wide aggregates are computed in Java using weighted averages
     * (weighted by consultant count for costs/hours, weighted by billable hours for rates).
     *
     * @return BreakEvenUtilizationDTO with companyWide aggregate and per-career-level list
     */
    public BreakEvenUtilizationDTO getBreakEvenUtilization() {
        log.debugf("Querying fact_minimum_viable_rate for break-even utilization");

        @SuppressWarnings("unchecked")
        List<Object[]> rows = em.createNativeQuery("""
                SELECT
                    career_level,
                    SUM(consultant_count) AS consultant_count,
                    SUM(total_monthly_cost_dkk * consultant_count) / SUM(consultant_count) AS total_monthly_cost_dkk,
                    SUM(avg_net_available_hours * consultant_count) / SUM(consultant_count) AS avg_net_available_hours,
                    CASE WHEN SUM(avg_net_available_hours * consultant_count) > 0
                         THEN SUM(actual_billable_hours * consultant_count) / SUM(avg_net_available_hours * consultant_count)
                         ELSE 0 END AS actual_utilization_ratio,
                    SUM(actual_billable_hours * consultant_count) / SUM(consultant_count) AS actual_billable_hours,
                    CASE WHEN SUM(actual_billable_hours * consultant_count) > 0
                         THEN SUM(avg_actual_billing_rate * actual_billable_hours * consultant_count) / SUM(actual_billable_hours * consultant_count)
                         ELSE 0 END AS avg_actual_billing_rate,
                    CASE WHEN SUM(avg_net_available_hours * consultant_count) > 0
                              AND SUM(avg_actual_billing_rate * actual_billable_hours * consultant_count) > 0
                         THEN SUM(total_monthly_cost_dkk * consultant_count) * SUM(actual_billable_hours * consultant_count)
                              / (SUM(avg_net_available_hours * consultant_count) * SUM(avg_actual_billing_rate * actual_billable_hours * consultant_count))
                         ELSE NULL END AS break_even_utilization_pct,
                    CASE WHEN SUM(avg_net_available_hours * consultant_count) > 0
                              AND SUM(avg_actual_billing_rate * actual_billable_hours * consultant_count) > 0
                         THEN SUM(total_monthly_cost_dkk * consultant_count) * SUM(actual_billable_hours * consultant_count)
                              / (0.85 * SUM(avg_net_available_hours * consultant_count) * SUM(avg_actual_billing_rate * actual_billable_hours * consultant_count))
                         ELSE NULL END AS break_even_utilization_15pct,
                    CASE WHEN SUM(avg_net_available_hours * consultant_count) > 0
                              AND SUM(avg_actual_billing_rate * actual_billable_hours * consultant_count) > 0
                         THEN SUM(total_monthly_cost_dkk * consultant_count) * SUM(actual_billable_hours * consultant_count)
                              / (0.80 * SUM(avg_net_available_hours * consultant_count) * SUM(avg_actual_billing_rate * actual_billable_hours * consultant_count))
                         ELSE NULL END AS break_even_utilization_20pct
                FROM fact_minimum_viable_rate
                GROUP BY career_level
                ORDER BY career_level
                """).getResultList();

        log.debugf("fact_minimum_viable_rate returned %d career-level rows", rows.size());

        List<BreakEvenCareerLevelDTO> byCareerLevel = new ArrayList<>(rows.size());

        // Accumulator variables for computing weighted company-wide aggregates
        double totalWeightedCost = 0.0;
        double totalWeightedHours = 0.0;
        double totalWeightedRateNumerator = 0.0;
        double totalWeightedRateDenominator = 0.0;
        double totalWeightedBillableHours = 0.0;
        double totalWeightedAvailableHours = 0.0;
        int totalConsultantCount = 0;

        for (Object[] row : rows) {
            String careerLevel       = (String) row[0];
            int consultantCount      = ((Number) row[1]).intValue();
            double monthlyCost       = ((Number) row[2]).doubleValue();
            double netAvailableHours = ((Number) row[3]).doubleValue();
            double actualUtilization = ((Number) row[4]).doubleValue();
            double actualBillableHrs = ((Number) row[5]).doubleValue();
            double avgBillingRate    = ((Number) row[6]).doubleValue();

            // NULL columns from the view come back as null when hours or rate is zero
            Double breakEven         = row[7] != null ? ((Number) row[7]).doubleValue() : null;
            Double breakEven15       = row[8] != null ? ((Number) row[8]).doubleValue() : null;
            Double breakEven20       = row[9] != null ? ((Number) row[9]).doubleValue() : null;

            String label = CAREER_LEVEL_LABELS.getOrDefault(careerLevel, toTitleCase(careerLevel));

            byCareerLevel.add(new BreakEvenCareerLevelDTO(
                    careerLevel,
                    label,
                    breakEven,
                    breakEven15,
                    breakEven20,
                    actualUtilization,
                    consultantCount,
                    avgBillingRate,
                    monthlyCost
            ));

            // Accumulate for company-wide weighted averages
            totalWeightedCost        += monthlyCost       * consultantCount;
            totalWeightedHours       += netAvailableHours * consultantCount;
            totalWeightedRateNumerator   += avgBillingRate * actualBillableHrs * consultantCount;
            totalWeightedRateDenominator += actualBillableHrs * consultantCount;
            totalWeightedBillableHours   += actualBillableHrs * consultantCount;
            totalWeightedAvailableHours  += netAvailableHours * consultantCount;
            totalConsultantCount     += consultantCount;
        }

        BreakEvenCompanyWideDTO companyWide = computeCompanyWideAggregate(
                totalWeightedCost,
                totalWeightedHours,
                totalWeightedRateNumerator,
                totalWeightedRateDenominator,
                totalWeightedBillableHours,
                totalWeightedAvailableHours,
                totalConsultantCount
        );

        log.debugf("Company-wide break-even: %.4f (0%%), %.4f (15%%), %.4f (20%%), actual: %.4f",
                companyWide.getBreakEvenUtilization(),
                companyWide.getBreakEvenUtilization15Margin(),
                companyWide.getBreakEvenUtilization20Margin(),
                companyWide.getActualUtilization());

        return new BreakEvenUtilizationDTO(companyWide, byCareerLevel);
    }

    /**
     * Computes company-wide aggregate from accumulated weighted sums.
     *
     * Weighted avg monthly cost: SUM(cost * count) / SUM(count)
     * Weighted avg net hours:    SUM(hours * count) / SUM(count)
     * Weighted avg billing rate: SUM(rate * billable_hours * count) / SUM(billable_hours * count)
     * Break-even:                weighted_cost / (weighted_hours * weighted_rate)
     * Actual utilization:        SUM(billable_hours * count) / SUM(net_hours * count)
     */
    private BreakEvenCompanyWideDTO computeCompanyWideAggregate(
            double totalWeightedCost,
            double totalWeightedHours,
            double totalWeightedRateNumerator,
            double totalWeightedRateDenominator,
            double totalWeightedBillableHours,
            double totalWeightedAvailableHours,
            int totalConsultantCount) {

        if (totalConsultantCount == 0) {
            log.warnf("No consultant data found in fact_minimum_viable_rate");
            return new BreakEvenCompanyWideDTO(null, null, null, 0.0, 0.0, 0.0);
        }

        double weightedAvgCost  = totalWeightedCost  / totalConsultantCount;
        double weightedAvgHours = totalWeightedHours / totalConsultantCount;

        double weightedAvgRate = totalWeightedRateDenominator > 0
                ? totalWeightedRateNumerator / totalWeightedRateDenominator
                : 0.0;

        double actualUtilization = totalWeightedAvailableHours > 0
                ? totalWeightedBillableHours / totalWeightedAvailableHours
                : 0.0;

        boolean canComputeBreakEven = weightedAvgHours > 0 && weightedAvgRate > 0;

        Double breakEven   = canComputeBreakEven
                ? weightedAvgCost / (weightedAvgHours * weightedAvgRate)
                : null;
        Double breakEven15 = canComputeBreakEven
                ? weightedAvgCost / (0.85 * weightedAvgHours * weightedAvgRate)
                : null;
        Double breakEven20 = canComputeBreakEven
                ? weightedAvgCost / (0.80 * weightedAvgHours * weightedAvgRate)
                : null;

        return new BreakEvenCompanyWideDTO(
                breakEven,
                breakEven15,
                breakEven20,
                actualUtilization,
                weightedAvgRate,
                weightedAvgCost
        );
    }

    /**
     * Converts an UPPER_SNAKE_CASE string to Title Case as a fallback label.
     * Example: "SENIOR_MANAGER" -> "Senior Manager"
     */
    private String toTitleCase(String upperSnakeCase) {
        if (upperSnakeCase == null || upperSnakeCase.isBlank()) {
            return upperSnakeCase;
        }
        String[] words = upperSnakeCase.split("_");
        StringBuilder sb = new StringBuilder();
        for (String word : words) {
            if (!sb.isEmpty()) {
                sb.append(' ');
            }
            if (!word.isEmpty()) {
                sb.append(Character.toUpperCase(word.charAt(0)));
                if (word.length() > 1) {
                    sb.append(word.substring(1).toLowerCase());
                }
            }
        }
        return sb.toString();
    }
}
