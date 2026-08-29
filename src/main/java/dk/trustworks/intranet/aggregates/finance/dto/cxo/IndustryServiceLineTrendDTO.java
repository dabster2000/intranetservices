package dk.trustworks.intranet.aggregates.finance.dto.cxo;

import java.util.List;
import java.util.Objects;

/**
 * Wrapper response for GET /clients/cxo/industry-service-line-trend.
 *
 * <p>Service-line penetration per industry segment for the trailing 12 full
 * calendar quarters: how many of a segment's active clients buy each service
 * line, and how many distinct service lines an average client uses.
 * {@link #quarters} is the ordered x-axis; every segment's {@code series} is
 * aligned index-for-index with it. {@link #serviceLines} lists every service
 * line that occurs anywhere in the window, in a stable order.</p>
 */
public record IndustryServiceLineTrendDTO(
        List<IndustryTrendQuarterDTO> quarters,
        List<ServiceLineMetaDTO> serviceLines,
        List<IndustryServiceLineSegmentDTO> industries,
        String latestFullQuarterKey
) {
    public IndustryServiceLineTrendDTO {
        Objects.requireNonNull(quarters, "quarters");
        Objects.requireNonNull(serviceLines, "serviceLines");
        Objects.requireNonNull(industries, "industries");
    }
}
