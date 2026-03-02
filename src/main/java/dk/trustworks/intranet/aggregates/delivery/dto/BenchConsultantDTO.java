package dk.trustworks.intranet.aggregates.delivery.dto;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * DTO representing an individual consultant currently on the bench.
 * A consultant is considered on bench when their trailing 28-day utilization is < 50%.
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class BenchConsultantDTO {

    /**
     * User UUID (FK to user.uuid).
     */
    private String uuid;

    /**
     * Full name: CONCAT(firstname, ' ', lastname) from user table.
     */
    private String name;

    /**
     * Most recent career level from user_career_level.career_level.
     * Empty string when no career level record exists for this consultant.
     */
    private String careerLevel;

    /**
     * Approximate number of weeks on bench in the trailing 28-day window.
     * Calculated as: COUNT(days with 0 billable hours) / 5
     */
    private int benchWeeks;
}
