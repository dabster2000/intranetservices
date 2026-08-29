package dk.trustworks.intranet.aggregates.finance.dto.cxo;

import java.util.List;
import java.util.Objects;

/**
 * One industry segment on the industry engagement trend chart.
 *
 * @param segmentCode  client segment code: PUBLIC, ENERGY, HEALTH, FINANCIAL,
 *                     EDUCATION or OTHER (unknown/missing segments are folded
 *                     into OTHER)
 * @param displayName  chart label, e.g. "Energy &amp; Utilities"
 * @param series       one point per entry in the response's {@code quarters}
 *                     list, same order
 * @param engagements  the segment's current engagements for the drill-down
 *                     panel (active in or after the latest full quarter),
 *                     sorted by running length descending
 */
public record IndustryEngagementSegmentDTO(
        String segmentCode,
        String displayName,
        List<IndustryEngagementPointDTO> series,
        List<IndustryEngagementItemDTO> engagements
) {
    public IndustryEngagementSegmentDTO {
        Objects.requireNonNull(segmentCode, "segmentCode");
        Objects.requireNonNull(series, "series");
        Objects.requireNonNull(engagements, "engagements");
    }
}
