package dk.trustworks.intranet.aggregates.delivery.dto;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * DTO for a single cell in the Resource Heatmap grid.
 * Represents utilization for one team in one week.
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class ResourceHeatmapCellDTO {

    /** Index into the teams array */
    private int teamIndex;

    /** Index into the weeks array */
    private int weekIndex;

    /** Utilization percentage for this team/week combination */
    private double utilizationPercent;
}
