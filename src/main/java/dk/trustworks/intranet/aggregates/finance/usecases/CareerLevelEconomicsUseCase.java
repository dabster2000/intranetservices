package dk.trustworks.intranet.aggregates.finance.usecases;

import dk.trustworks.intranet.aggregates.finance.dto.CareerLevelConsultantDTO;
import dk.trustworks.intranet.aggregates.finance.dto.CareerLevelConsultantsDTO;
import dk.trustworks.intranet.aggregates.finance.dto.CareerLevelEconomicsDTO;
import dk.trustworks.intranet.aggregates.finance.dto.CareerLevelEconomicsItemDTO;
import dk.trustworks.intranet.aggregates.finance.model.CareerLevelBonus;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.persistence.EntityManager;
import lombok.extern.jbosslog.JBossLog;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * Use case for retrieving career-level cost economics from the fact_minimum_viable_rate view.
 *
 * <p>Returns a full cost breakdown per career level — salary, pension, statutory costs,
 * benefits, overhead allocations — alongside rate adequacy metrics (break-even rate,
 * minimum viable rates with 15%/20% margin, and the rate buffer against actuals).</p>
 *
 * <p>This use case is the data source for the "Career Level Cost Structure" section
 * of the CXO Dashboard Cost Overview tab.</p>
 *
 * <p>{@code statutoryCosts} is computed in Java as
 * {@code atp_per_person_dkk + am_bidrag_per_person_dkk} to keep the SQL simple
 * and the semantic clearly expressed in code.</p>
 */
@JBossLog
@ApplicationScoped
public class CareerLevelEconomicsUseCase {

    /**
     * Maps raw career_level strings from the DB to human-readable display labels.
     * Mirrors the mapping used in BreakEvenUtilizationUseCase for consistency.
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
     * Retrieves career-level cost economics for all career levels, optionally filtered
     * by a set of company UUIDs.
     *
     * <p>When {@code companyIds} is null or empty, all companies in the view are included.
     * When provided, only rows matching the given company IDs are returned.</p>
     *
     * @param companyIds optional set of company UUIDs to filter by; null means no filter
     * @return CareerLevelEconomicsDTO with a list of per-career-level items, never null
     */
    public CareerLevelEconomicsDTO getCareerLevelEconomics(Set<String> companyIds) {
        log.debugf("Querying fact_minimum_viable_rate for career-level economics, companyIds=%s", companyIds);

        @SuppressWarnings("unchecked")
        List<Object[]> rows = buildAndExecuteQuery(companyIds);

        log.debugf("fact_minimum_viable_rate returned %d career-level rows", rows.size());

        // Fetch bonus percentages per career level
        Map<String, Double> bonusMap = CareerLevelBonus.<CareerLevelBonus>listAll().stream()
                .collect(Collectors.toMap(b -> b.careerLevel, b -> b.bonusPct.doubleValue()));

        List<CareerLevelEconomicsItemDTO> items = new ArrayList<>(rows.size());

        for (Object[] row : rows) {
            String careerLevel       = (String)  row[0];
            int    consultantCount   = ((Number)  row[1]).intValue();
            double avgMonthlySalary  = ((Number)  row[2]).doubleValue();
            double employerPension   = ((Number)  row[3]).doubleValue();
            double atpPerPerson      = ((Number)  row[4]).doubleValue();
            double amBidragPerPerson = ((Number)  row[5]).doubleValue();
            double benefits          = ((Number)  row[6]).doubleValue();
            double staffAllocation   = ((Number)  row[7]).doubleValue();
            double overheadAllocation= ((Number)  row[8]).doubleValue();
            double totalMonthlyCost  = ((Number)  row[9]).doubleValue();
            double actualUtilization = ((Number)  row[10]).doubleValue();
            double avgBillingRate    = ((Number)  row[11]).doubleValue();

            // NULL when avg_net_available_hours or actual_utilization_ratio is zero
            Double breakEvenRateTarget = row[12] != null ? ((Number) row[12]).doubleValue() : null;
            Double rateWith15Margin    = row[13] != null ? ((Number) row[13]).doubleValue() : null;
            Double rateWith20Margin    = row[14] != null ? ((Number) row[14]).doubleValue() : null;
            Double rateBuffer          = row[15] != null ? ((Number) row[15]).doubleValue() : null;

            // Min/max salary across all consultants at this career level (nullable)
            Integer minMonthlySalary   = row[16] != null ? ((Number) row[16]).intValue() : null;
            Integer maxMonthlySalary   = row[17] != null ? ((Number) row[17]).intValue() : null;

            // Sum statutory costs in Java: ATP + AM-bidrag
            double statutoryCosts = atpPerPerson + amBidragPerPerson;

            // Apply bonus percentage: bonusCost = avgMonthlySalary * (bonusPct / 100)
            double bonusPct = bonusMap.getOrDefault(careerLevel, 0.0);
            if (bonusPct > 0 && totalMonthlyCost > 0) {
                double bonusCost = avgMonthlySalary * bonusPct / 100.0;
                double newTotalCost = totalMonthlyCost + bonusCost;
                double costRatio = newTotalCost / totalMonthlyCost;
                totalMonthlyCost = newTotalCost;
                breakEvenRateTarget = breakEvenRateTarget != null ? breakEvenRateTarget * costRatio : null;
                rateWith15Margin = rateWith15Margin != null ? rateWith15Margin * costRatio : null;
                rateWith20Margin = rateWith20Margin != null ? rateWith20Margin * costRatio : null;
                rateBuffer = (breakEvenRateTarget != null && avgBillingRate > 0)
                        ? avgBillingRate - breakEvenRateTarget : null;
            }

            String label = CAREER_LEVEL_LABELS.getOrDefault(careerLevel, toTitleCase(careerLevel));

            items.add(new CareerLevelEconomicsItemDTO(
                    careerLevel,
                    label,
                    consultantCount,
                    avgMonthlySalary,
                    employerPension,
                    statutoryCosts,
                    benefits,
                    staffAllocation,
                    overheadAllocation,
                    totalMonthlyCost,
                    actualUtilization,
                    avgBillingRate,
                    breakEvenRateTarget,
                    rateWith15Margin,
                    rateWith20Margin,
                    rateBuffer,
                    minMonthlySalary,
                    maxMonthlySalary,
                    bonusPct
            ));
        }

        return new CareerLevelEconomicsDTO(items);
    }

    /**
     * Retrieves individual consultants at a specific career level, optionally filtered
     * by a set of company UUIDs.
     *
     * <p>Uses the same join pattern as the {@code fact_minimum_viable_rate} view (V178):
     * point-in-time {@code user_career_level} lookup, active consultant status,
     * and current salary.</p>
     *
     * @param careerLevel the career level key to filter by (e.g., "SENIOR", "JUNIOR")
     * @param companyIds  optional set of company UUIDs to filter by; null means no filter
     * @return CareerLevelConsultantsDTO with the career level metadata and individual consultants
     */
    @SuppressWarnings("unchecked")
    public CareerLevelConsultantsDTO getConsultantsByCareerLevel(String careerLevel, Set<String> companyIds) {
        log.debugf("Querying consultants for career level=%s, companyIds=%s", careerLevel, companyIds);

        boolean hasCompanyFilter = companyIds != null && !companyIds.isEmpty();

        String sql = """
                SELECT
                    u.uuid,
                    u.firstname,
                    u.lastname,
                    s.salary,
                    u.photoconsent
                FROM user u
                INNER JOIN userstatus us ON us.useruuid = u.uuid
                INNER JOIN (
                    SELECT ucl.useruuid, ucl.career_level
                    FROM user_career_level ucl
                    INNER JOIN (
                        SELECT useruuid, MAX(active_from) AS max_active_from
                        FROM user_career_level
                        WHERE active_from <= CURDATE()
                        GROUP BY useruuid
                    ) latest ON ucl.useruuid = latest.useruuid AND ucl.active_from = latest.max_active_from
                ) ccl ON ccl.useruuid = u.uuid
                INNER JOIN salary s ON s.useruuid = u.uuid
                WHERE u.type = 'USER'
                  AND us.type = 'CONSULTANT'
                  AND us.status = 'ACTIVE'
                  AND us.statusdate = (
                      SELECT MAX(us2.statusdate)
                      FROM userstatus us2
                      WHERE us2.useruuid = u.uuid
                        AND us2.statusdate <= CURDATE()
                  )
                  AND s.activefrom = (
                      SELECT MAX(s2.activefrom)
                      FROM salary s2
                      WHERE s2.useruuid = u.uuid
                        AND s2.activefrom <= CURDATE()
                  )
                  AND ccl.career_level = :careerLevel
                """ + (hasCompanyFilter ? "  AND us.companyuuid IN :companyIds\n" : "") + """
                ORDER BY s.salary DESC
                """;

        var query = em.createNativeQuery(sql)
                .setParameter("careerLevel", careerLevel);

        if (hasCompanyFilter) {
            query.setParameter("companyIds", companyIds);
        }

        List<Object[]> rows = query.getResultList();

        log.debugf("Found %d consultants at career level %s", rows.size(), careerLevel);

        List<CareerLevelConsultantDTO> consultants = new ArrayList<>(rows.size());
        for (Object[] row : rows) {
            String uuid      = (String) row[0];
            String firstname = (String) row[1];
            String lastname  = (String) row[2];
            double salary    = ((Number) row[3]).doubleValue();
            boolean consent  = row[4] != null && ((Number) row[4]).intValue() != 0;

            consultants.add(new CareerLevelConsultantDTO(uuid, firstname, lastname, salary, consent));
        }

        String label = CAREER_LEVEL_LABELS.getOrDefault(careerLevel, toTitleCase(careerLevel));

        return new CareerLevelConsultantsDTO(careerLevel, label, consultants);
    }

    /**
     * Builds and executes the native query against fact_minimum_viable_rate.
     * Adds a company_id IN-filter when companyIds is non-empty.
     */
    @SuppressWarnings("unchecked")
    private List<Object[]> buildAndExecuteQuery(Set<String> companyIds) {
        boolean hasCompanyFilter = companyIds != null && !companyIds.isEmpty();

        String whereClause = hasCompanyFilter ? "WHERE company_id IN :companyIds\n" : "";

        String sql = """
                SELECT
                    career_level,
                    SUM(consultant_count) AS consultant_count,
                    SUM(avg_monthly_salary_dkk * consultant_count) / SUM(consultant_count) AS avg_monthly_salary_dkk,
                    SUM(employer_pension_dkk * consultant_count) / SUM(consultant_count) AS employer_pension_dkk,
                    SUM(atp_per_person_dkk * consultant_count) / SUM(consultant_count) AS atp_per_person_dkk,
                    SUM(am_bidrag_per_person_dkk * consultant_count) / SUM(consultant_count) AS am_bidrag_per_person_dkk,
                    SUM(benefit_per_person_dkk * consultant_count) / SUM(consultant_count) AS benefit_per_person_dkk,
                    SUM(staff_allocation_dkk * consultant_count) / SUM(consultant_count) AS staff_allocation_dkk,
                    SUM(overhead_allocation_dkk * consultant_count) / SUM(consultant_count) AS overhead_allocation_dkk,
                    SUM(total_monthly_cost_dkk * consultant_count) / SUM(consultant_count) AS total_monthly_cost_dkk,
                    SUM(actual_utilization_ratio * consultant_count) / SUM(consultant_count) AS actual_utilization_ratio,
                    SUM(avg_actual_billing_rate * consultant_count) / SUM(consultant_count) AS avg_actual_billing_rate,
                    CASE WHEN SUM(CASE WHEN break_even_rate_target IS NOT NULL THEN consultant_count ELSE 0 END) > 0
                        THEN SUM(COALESCE(break_even_rate_target, 0) * consultant_count) / SUM(CASE WHEN break_even_rate_target IS NOT NULL THEN consultant_count ELSE 0 END)
                        ELSE NULL END AS break_even_rate_target,
                    CASE WHEN SUM(CASE WHEN min_rate_15pct_margin IS NOT NULL THEN consultant_count ELSE 0 END) > 0
                        THEN SUM(COALESCE(min_rate_15pct_margin, 0) * consultant_count) / SUM(CASE WHEN min_rate_15pct_margin IS NOT NULL THEN consultant_count ELSE 0 END)
                        ELSE NULL END AS min_rate_15pct_margin,
                    CASE WHEN SUM(CASE WHEN min_rate_20pct_margin IS NOT NULL THEN consultant_count ELSE 0 END) > 0
                        THEN SUM(COALESCE(min_rate_20pct_margin, 0) * consultant_count) / SUM(CASE WHEN min_rate_20pct_margin IS NOT NULL THEN consultant_count ELSE 0 END)
                        ELSE NULL END AS min_rate_20pct_margin,
                    CASE WHEN SUM(CASE WHEN rate_buffer_dkk IS NOT NULL THEN consultant_count ELSE 0 END) > 0
                        THEN SUM(COALESCE(rate_buffer_dkk, 0) * consultant_count) / SUM(CASE WHEN rate_buffer_dkk IS NOT NULL THEN consultant_count ELSE 0 END)
                        ELSE NULL END AS rate_buffer_dkk,
                    MIN(min_monthly_salary_dkk) AS min_monthly_salary_dkk,
                    MAX(max_monthly_salary_dkk) AS max_monthly_salary_dkk
                FROM fact_minimum_viable_rate_mat
                """ + whereClause + """
                GROUP BY career_level
                ORDER BY career_level
                """;

        var query = em.createNativeQuery(sql);

        if (hasCompanyFilter) {
            query.setParameter("companyIds", companyIds);
        }

        return query.getResultList();
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
        var sb = new StringBuilder();
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
