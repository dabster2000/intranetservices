package dk.trustworks.intranet.aggregates.jkdashboard.dto;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * Client concentration data for treemap visualization.
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class ClientConcentrationEntry {
    private String clientUuid;
    private String clientName;
    private int jkCount;
    private double totalHours;
    private double potentialRevenue;
    private double avgRate;
}
