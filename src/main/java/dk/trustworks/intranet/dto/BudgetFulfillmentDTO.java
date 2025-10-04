package dk.trustworks.intranet.dto;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;
import org.eclipse.microprofile.openapi.annotations.media.Schema;

import java.time.LocalDate;

/**
 * Budget fulfillment data transfer object containing comprehensive metrics
 * for analyzing consultant performance against budget targets.
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
@Schema(description = "Budget fulfillment metrics for a consultant including available hours, budget hours, registered hours, and calculated utilization ratios")
public class BudgetFulfillmentDTO {

    @Schema(description = "The month for this data (first day of month)",
            example = "2024-07-01",
            required = true)
    private LocalDate month;

    @Schema(description = "Net available hours (gross hours minus all leave types: vacation, sick, maternity, paid/unpaid leave, unavailable)",
            example = "100.0",
            required = true)
    private Double netAvailableHours;

    @Schema(description = "Budget hours allocated from contracts (adjusted for availability)",
            example = "80.0",
            required = true)
    private Double budgetHours;

    @Schema(description = "Actual billable hours registered by the consultant",
            example = "60.0",
            required = true)
    private Double registeredBillableHours;

    @Schema(description = "Budget utilization (budgetHours / netAvailableHours) - represents expected work level",
            example = "0.80",
            required = true)
    private Double budgetUtilization;

    @Schema(description = "Actual utilization (registeredBillableHours / netAvailableHours) - represents actual work level",
            example = "0.60",
            required = true)
    private Double actualUtilization;

    @Schema(description = "Budget fulfillment (registeredBillableHours / budgetHours) - represents performance against budget target. 1.0 = 100% fulfillment, <1.0 = under-delivery, >1.0 = over-delivery",
            example = "0.75",
            required = true)
    private Double budgetFulfillment;

    /**
     * Factory method to create BudgetFulfillmentDTO with calculated utilization metrics.
     *
     * @param month The month for this data
     * @param netAvailableHours Net available hours (gross - all leave types)
     * @param budgetHours Budget hours allocated
     * @param registeredBillableHours Actual billable hours worked
     * @return BudgetFulfillmentDTO with all metrics calculated
     */
    public static BudgetFulfillmentDTO create(LocalDate month, Double netAvailableHours,
                                               Double budgetHours, Double registeredBillableHours) {
        Double budgetUtilization = (netAvailableHours != null && netAvailableHours > 0.0 && budgetHours != null)
            ? budgetHours / netAvailableHours
            : 0.0;

        Double actualUtilization = (netAvailableHours != null && netAvailableHours > 0.0 && registeredBillableHours != null)
            ? registeredBillableHours / netAvailableHours
            : 0.0;

        Double budgetFulfillment = (budgetHours != null && budgetHours > 0.0 && registeredBillableHours != null)
            ? registeredBillableHours / budgetHours
            : 0.0;

        return new BudgetFulfillmentDTO(
            month,
            netAvailableHours != null ? netAvailableHours : 0.0,
            budgetHours != null ? budgetHours : 0.0,
            registeredBillableHours != null ? registeredBillableHours : 0.0,
            budgetUtilization,
            actualUtilization,
            budgetFulfillment
        );
    }
}
