package dk.trustworks.intranet.apigateway.dto;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;

@Data
@NoArgsConstructor
@AllArgsConstructor
public class TwBonusCalculationDTO {
    private int fiscalYear;
    private List<CompanyBonusDTO> companies;
    private List<EmployeeBonusDTO> employees;
    /**
     * The single bonus factor actually applied to every employee, regardless of company.
     * Equals the highest per-company factor ({@link CompanyBonusDTO#factor}).
     */
    private double appliedFactor;
    /** UUID of the company whose factor was selected as {@link #appliedFactor}. */
    private String winningCompanyUuid;
    /** Sum of every employee's payout (= appliedFactor applied to all weight, rounded per employee). */
    private double projectedTotalPayout;

    @Data
    @NoArgsConstructor
    @AllArgsConstructor
    public static class CompanyBonusDTO {
        private String companyUuid;
        private String companyName;
        private double profitBeforeTax;
        private double bonusPercent;
        private double extraPool;
        private double pool;
        /** Base (career-adjusted) weight sum — pool ÷ totalWeight reads the true factor. */
        private double totalWeight;
        private double factor;
        private int eligibleCount;
        /** "Ekstra (karriere)" — round(F * (effectiveWeight - baseWeight)). */
        private double careerExtra;
        /** "Samlet udbetaling" — round(F * effectiveWeight). */
        private double totalPayout;
    }

    @Data
    @NoArgsConstructor
    @AllArgsConstructor
    public static class EmployeeBonusDTO {
        private String useruuid;
        private String fullname;
        private List<CompanyContributionDTO> companyContributions;
        /** Base weight sum (Σ b[m]) — what "Σ Vægt" shows everywhere. */
        private double totalWeightSum;
        private double totalPayout;
        private double bonusFactor;
        /** Effective weight sum (Σ e[m] = Σ w[m]*mult[m]). */
        private double effectiveWeightSum;
        /** Representative career multiplier for display. */
        private double multiplier;
        /** Representative career level name ("" when careerIneligible). */
        private String representativeCareerLevel;
        /** True when the employee had salary but every month resolves to a 0× level. */
        private boolean careerIneligible;
    }

    @Data
    @NoArgsConstructor
    @AllArgsConstructor
    public static class CompanyContributionDTO {
        private String companyUuid;
        private double[] months;          // 12 RAW base weights (unchanged semantics)
        private double[] multipliers;     // 12 per-month multipliers (0/1/1.5/2/3)
        private double baseWeightSum;     // Σ b[m] for this company (eligible only)
        private double effectiveWeightSum;// Σ e[m] for this company
        private double weightSum;         // KEEP — equals baseWeightSum (FE reads .weightSum)
        private double payout;            // round(effectiveWeightSum * F)
    }
}
