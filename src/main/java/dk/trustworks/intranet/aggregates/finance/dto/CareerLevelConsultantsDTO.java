package dk.trustworks.intranet.aggregates.finance.dto;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;

/**
 * Wrapper DTO for the career-level consultants drill-down response.
 *
 * <p>Returned by {@code GET /finance/cxo/career-level-consultants}.
 * Contains the career level metadata and the list of individual consultants at that level.</p>
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class CareerLevelConsultantsDTO {

    /** Career level enum string (e.g., "SENIOR", "JUNIOR", "MANAGER"). */
    private String careerLevel;

    /** Human-readable label for the career level (e.g., "Senior Consultant", "Junior"). */
    private String careerLevelLabel;

    /** Individual consultants at this career level, ordered by salary descending. */
    private List<CareerLevelConsultantDTO> consultants;
}
