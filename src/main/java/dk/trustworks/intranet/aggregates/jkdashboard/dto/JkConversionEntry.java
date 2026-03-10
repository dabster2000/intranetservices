package dk.trustworks.intranet.aggregates.jkdashboard.dto;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDate;

/**
 * One entry in the STUDENT-to-CONSULTANT conversion timeline.
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class JkConversionEntry {
    private String jkUuid;
    private String jkName;
    private LocalDate studentStartDate;
    private LocalDate consultantStartDate;
    private int durationMonths;
    private boolean isConverted;
}
