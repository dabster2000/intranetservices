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
        private double totalWeight;
        private double factor;
        private int eligibleCount;
    }

    @Data
    @NoArgsConstructor
    @AllArgsConstructor
    public static class EmployeeBonusDTO {
        private String useruuid;
        private String fullname;
        private List<CompanyContributionDTO> companyContributions;
        private double totalWeightSum;
        private double totalPayout;
        private double bonusFactor;
    }

    @Data
    @NoArgsConstructor
    @AllArgsConstructor
    public static class CompanyContributionDTO {
        private String companyUuid;
        private double[] months; // 12 monthly weights
        private double weightSum;
        private double payout;
    }
}
