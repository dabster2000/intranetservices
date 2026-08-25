package dk.trustworks.intranet.aggregates.finance.dto.cxo;

import java.util.List;
import java.util.Objects;

/**
 * Wrapper response for GET /clients/cxo/industry-revenue-trend.
 *
 * <p>Quarterly invoiced revenue (trailing 12 full calendar quarters) plus
 * budget-based forecast (current quarter + the following 6 months, i.e. 3
 * quarters) per industry segment. {@link #quarters} is the ordered x-axis;
 * every segment's {@code series} is aligned index-for-index with it.</p>
 */
public record IndustryRevenueTrendDTO(
        List<IndustryTrendQuarterDTO> quarters,
        List<IndustryTrendSegmentDTO> industries,
        String latestFullQuarterKey,
        String currentQuarterKey
) {
    public IndustryRevenueTrendDTO {
        Objects.requireNonNull(quarters, "quarters");
        Objects.requireNonNull(industries, "industries");
    }
}
