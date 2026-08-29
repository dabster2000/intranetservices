package dk.trustworks.intranet.aggregates.finance.dto.cxo;

import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * One industry segment on the industry service-line trend chart.
 *
 * @param segmentCode         client segment code: PUBLIC, ENERGY, HEALTH,
 *                            FINANCIAL, EDUCATION or OTHER
 * @param displayName         chart label, e.g. "Energy &amp; Utilities"
 * @param series              one point per entry in the response's
 *                            {@code quarters} list, same order
 * @param latestQuarterClients  for the drill-down panel: client names with
 *                            revenue on each service line in the latest full
 *                            quarter, keyed by service-line code
 */
public record IndustryServiceLineSegmentDTO(
        String segmentCode,
        String displayName,
        List<IndustryServiceLinePointDTO> series,
        Map<String, List<String>> latestQuarterClients
) {
    public IndustryServiceLineSegmentDTO {
        Objects.requireNonNull(segmentCode, "segmentCode");
        Objects.requireNonNull(series, "series");
        Objects.requireNonNull(latestQuarterClients, "latestQuarterClients");
    }
}
