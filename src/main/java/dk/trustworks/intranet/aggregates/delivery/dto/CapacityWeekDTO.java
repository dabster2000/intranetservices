package dk.trustworks.intranet.aggregates.delivery.dto;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * DTO for a single week in the Capacity Planning view.
 * Represents FTE allocation data for one week.
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class CapacityWeekDTO {

    /** ISO week key (e.g., "2026-W07") */
    private String week;

    /** Human-readable label (e.g., "Feb 9") */
    private String weekLabel;

    /** Total available FTEs for this week */
    private double availableFTE;

    /** FTEs allocated to billable projects */
    private double allocatedFTE;

    /** FTEs on bench (available - allocated) */
    private double benchFTE;

    /** Whether this week is a forecast (true) or historical (false) */
    private boolean isForecast;
}
