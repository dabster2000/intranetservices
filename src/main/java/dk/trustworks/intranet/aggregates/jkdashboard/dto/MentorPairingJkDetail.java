package dk.trustworks.intranet.aggregates.jkdashboard.dto;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * Detail of one JK paired with a senior mentor.
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class MentorPairingJkDetail {
    private String jkUuid;
    private String jkName;
    private double pairedHours;
    private String type;
}
