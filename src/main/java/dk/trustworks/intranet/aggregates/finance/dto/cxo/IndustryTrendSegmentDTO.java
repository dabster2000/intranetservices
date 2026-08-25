package dk.trustworks.intranet.aggregates.finance.dto.cxo;

import java.util.List;
import java.util.Objects;

/**
 * One industry segment on the industry revenue trend chart.
 *
 * @param segmentCode  client segment code: PUBLIC, ENERGY, HEALTH, FINANCIAL,
 *                     EDUCATION or OTHER (unknown/missing segments are folded
 *                     into OTHER)
 * @param displayName  chart label, e.g. "Energy &amp; Utilities"
 * @param series       one point per entry in the response's {@code quarters}
 *                     list, same order
 * @param clients      the segment's clients for the drill-down panel, sorted by
 *                     window revenue descending (budget-only clients last)
 */
public record IndustryTrendSegmentDTO(
        String segmentCode,
        String displayName,
        List<IndustryTrendPointDTO> series,
        List<IndustryTrendClientDTO> clients
) {
    public IndustryTrendSegmentDTO {
        Objects.requireNonNull(segmentCode, "segmentCode");
        Objects.requireNonNull(series, "series");
        Objects.requireNonNull(clients, "clients");
    }
}
