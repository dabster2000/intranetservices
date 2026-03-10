package dk.trustworks.intranet.aggregates.jkdashboard.dto;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;

/**
 * One senior mentor and their JK pairings.
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class MentorPairingEntry {
    private String seniorUuid;
    private String seniorName;
    private int jkCount;
    private double totalPairedHours;
    private List<String> clients;
    private String pairingType;
    private List<MentorPairingJkDetail> jkDetails;
}
