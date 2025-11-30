package dk.trustworks.intranet.aggregates.finance.dto;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * DTO representing year-over-year OPEX comparison for waterfall chart.
 * Compares current fiscal year OPEX against prior fiscal year with category-level changes.
 *
 * Used by Cost Overview Dashboard to display OPEX Bridge (Waterfall) chart.
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class OpexBridgeDTO {

    /** Total OPEX for prior fiscal year in DKK */
    private double priorFYOpex;

    /** Year-over-year change in PEOPLE_NON_BILLABLE category in DKK (can be negative) */
    private double peopleNonBillableChange;

    /** Year-over-year change in TOOLS_SOFTWARE category in DKK (can be negative) */
    private double toolsSoftwareChange;

    /** Year-over-year change in OFFICE_FACILITIES category in DKK (can be negative) */
    private double officeFacilitiesChange;

    /** Year-over-year change in SALES_MARKETING category in DKK (can be negative) */
    private double salesMarketingChange;

    /** Year-over-year change in OTHER_OPEX category in DKK (can be negative) */
    private double otherOpexChange;

    /** Total OPEX for current fiscal year in DKK */
    private double currentFYOpex;

    /** Prior fiscal year (e.g., 2023 for FY2023/24 which runs Jul 2023 - Jun 2024) */
    private int priorFiscalYear;

    /** Current fiscal year (e.g., 2024 for FY2024/25 which runs Jul 2024 - Jun 2025) */
    private int currentFiscalYear;

    /** Year-over-year change percentage: (currentFYOpex - priorFYOpex) / priorFYOpex * 100 */
    private double yoyChangePercent;
}
