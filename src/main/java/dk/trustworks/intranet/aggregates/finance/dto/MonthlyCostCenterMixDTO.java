package dk.trustworks.intranet.aggregates.finance.dto;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * DTO representing monthly expense mix breakdown by cost center for 100% stacked column chart.
 * Shows absolute amounts and percentages of total OPEX for each cost center.
 *
 * Used by Cost Overview Dashboard to display Expense Mix by Cost Center chart.
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class MonthlyCostCenterMixDTO {

    /** User-friendly month label (e.g., "Jan 2024") */
    private String monthLabel;

    /** Month key in format YYYYMM (e.g., "202401") */
    private String monthKey;

    // Cost center breakdown (absolute amounts in DKK)

    /** HR_ADMIN cost center OPEX in DKK */
    private double hrAdmin;

    /** SALES cost center OPEX in DKK */
    private double sales;

    /** INTERNAL_IT cost center OPEX in DKK */
    private double internalIT;

    /** FACILITIES cost center OPEX in DKK */
    private double facilities;

    /** ADMIN cost center OPEX in DKK */
    private double admin;

    /** GENERAL cost center OPEX in DKK */
    private double general;

    /** Total OPEX for the month in DKK (sum of all cost centers) */
    private double totalOpex;

    // Percentages (for 100% stacked chart and tooltips)

    /** HR_ADMIN as percentage of total OPEX */
    private double hrAdminPercent;

    /** SALES as percentage of total OPEX */
    private double salesPercent;

    /** INTERNAL_IT as percentage of total OPEX */
    private double internalITPercent;

    /** FACILITIES as percentage of total OPEX */
    private double facilitiesPercent;

    /** ADMIN as percentage of total OPEX */
    private double adminPercent;

    /** GENERAL as percentage of total OPEX */
    private double generalPercent;
}
