package dk.trustworks.intranet.aggregates.jkdashboard.dto;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;

/**
 * Summary row for one JK in the team overview grid.
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class JkTeamMemberSummary {
    private String jkUuid;
    private String jkName;
    private String currentStatus;
    private int monthsAsStudent;
    private double salaryHours;
    private double clientHours;
    private double billingPercent;
    private double avgRate;
    private List<String> topClients;
    private double netProfitLoss;
    private boolean isUnassigned;
    private int unassignedMonths;
}
