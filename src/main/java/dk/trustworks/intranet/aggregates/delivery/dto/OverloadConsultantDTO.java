package dk.trustworks.intranet.aggregates.delivery.dto;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * DTO representing an individual consultant currently overloaded.
 * A consultant is considered overloaded when their trailing 28-day utilization is > 95%.
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class OverloadConsultantDTO {

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
     * Allocation percentage in the trailing 28-day window.
     * Calculated as: (billable_hours / net_available_hours) * 100
     * Rounded to 1 decimal place.
     */
    private double allocationPercent;
}
