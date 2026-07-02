package dk.trustworks.intranet.aggregates.finance.dto;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * DTO representing a consultant's profitability over a reporting window
 * (TTM for the CXO dashboard, fiscal-year based for the team dashboard).
 * Field names keep the historical "ttm" prefix — they are part of the JSON contract.
 *
 * Net Profit = Registered Revenue - Salary - Shared Overhead
 * Shared Overhead = Total OPEX (non-salary) / consultant headcount (equal split).
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class UnprofitableConsultantDTO {

    /** User UUID */
    private String userId;

    /** Consultant first name */
    private String firstname;

    /** Consultant last name */
    private String lastname;

    /** Practice code (PM, BA, SA, CYB, DEV) */
    private String practice;

    /** Total registered revenue in the reporting window (DKK) */
    private double ttmRevenue;

    /** Total salary cost in the reporting window (DKK), computed as sum of monthly max salary */
    private double ttmSalary;

    /** Equal share of total non-salary OPEX across all active consultants (DKK) */
    private double sharedOverhead;

    /** Net profit = ttmRevenue - ttmSalary - sharedOverhead. Negative means unprofitable. */
    private double netProfit;
}
