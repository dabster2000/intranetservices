package dk.trustworks.intranet.aggregates.finance.dto.cxo;

import java.util.List;
import java.util.Objects;

/**
 * One industry segment on the industry rate trend chart.
 *
 * @param segmentCode  client segment code: PUBLIC, ENERGY, HEALTH, FINANCIAL,
 *                     EDUCATION or OTHER (unknown/missing segments are folded
 *                     into OTHER)
 * @param displayName  chart label, e.g. "Energy &amp; Utilities"
 * @param series       one point per entry in the response's {@code quarters}
 *                     list, same order
 * @param clients      the segment's clients for the drill-down panel, sorted by
 *                     window hours descending
 */
public record IndustryRateSegmentDTO(
        String segmentCode,
        String displayName,
        List<IndustryRatePointDTO> series,
        List<IndustryRateClientDTO> clients
) {
    public IndustryRateSegmentDTO {
        Objects.requireNonNull(segmentCode, "segmentCode");
        Objects.requireNonNull(series, "series");
        Objects.requireNonNull(clients, "clients");
    }
}
