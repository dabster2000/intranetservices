package dk.trustworks.intranet.aggregates.delivery.dto;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;

/**
 * DTO for Resource Allocation Heatmap data.
 * Returns team utilization by week for trailing 8 weeks.
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class ResourceHeatmapDTO {

    /** Ordered list of team/practice names */
    private List<String> teams;

    /** Ordered list of week labels (e.g., "Jan 6", "Jan 13") */
    private List<String> weeks;

    /** Utilization data cells (team x week) */
    private List<ResourceHeatmapCellDTO> data;
}
