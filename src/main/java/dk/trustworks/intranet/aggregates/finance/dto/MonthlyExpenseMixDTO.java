package dk.trustworks.intranet.aggregates.finance.dto;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * DTO representing monthly expense mix breakdown by category for 100% stacked column chart.
 * Shows absolute amounts and percentages of total OPEX for each expense category.
 *
 * Used by Cost Overview Dashboard to display Expense Mix by Category chart.
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class MonthlyExpenseMixDTO {

    /** User-friendly month label (e.g., "Jan 2024") */
    private String monthLabel;

    /** Month key in format YYYYMM (e.g., "202401") */
    private String monthKey;

    // Category breakdown (absolute amounts in DKK)

    /** PEOPLE_NON_BILLABLE category OPEX in DKK */
    private double peopleNonBillable;

    /** TOOLS_SOFTWARE category OPEX in DKK */
    private double toolsSoftware;

    /** OFFICE_FACILITIES category OPEX in DKK */
    private double officeFacilities;

    /** SALES_MARKETING category OPEX in DKK */
    private double salesMarketing;

    /** OTHER_OPEX category OPEX in DKK */
    private double otherOpex;

    /** Total OPEX for the month in DKK (sum of all categories) */
    private double totalOpex;

    // Percentages (for 100% stacked chart and tooltips)

    /** PEOPLE_NON_BILLABLE as percentage of total OPEX */
    private double peopleNonBillablePercent;

    /** TOOLS_SOFTWARE as percentage of total OPEX */
    private double toolsSoftwarePercent;

    /** OFFICE_FACILITIES as percentage of total OPEX */
    private double officeFacilitiesPercent;

    /** SALES_MARKETING as percentage of total OPEX */
    private double salesMarketingPercent;

    /** OTHER_OPEX as percentage of total OPEX */
    private double otherOpexPercent;
}
