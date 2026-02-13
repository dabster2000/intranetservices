package dk.trustworks.intranet.aggregates.delivery.dto;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;

/**
 * DTO for Capacity Planning chart data.
 * Returns a 13-week view: 4 weeks historical + current week + 8 weeks forecast.
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class CapacityPlanningDTO {

    /** Ordered list of weekly capacity data (oldest first) */
    private List<CapacityWeekDTO> weeks;
}
