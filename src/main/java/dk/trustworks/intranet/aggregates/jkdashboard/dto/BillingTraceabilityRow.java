package dk.trustworks.intranet.aggregates.jkdashboard.dto;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * One row in the billing traceability grid, representing a single
 * JK + client + project + month combination with its billing classification.
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class BillingTraceabilityRow {
    private String jkUuid;
    private String jkName;
    private String clientUuid;
    private String clientName;
    private String projectUuid;
    private String month;
    private double registeredHours;
    private double invoicedHours;
    private BillingScenario scenario;
    private double avgRate;
    private String invoiceStatus;
    private MergeConfidence mergeConfidence;
    private MergeDetail mergeDetails;
}
