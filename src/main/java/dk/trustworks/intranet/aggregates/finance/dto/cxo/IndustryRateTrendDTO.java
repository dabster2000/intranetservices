package dk.trustworks.intranet.aggregates.finance.dto.cxo;

import java.util.List;
import java.util.Objects;

/**
 * Wrapper response for GET /clients/cxo/industry-rate-trend.
 *
 * <p>Hours-weighted average billable rate per industry segment for the trailing
 * 12 full calendar quarters, derived from registered work (the work_full view
 * semantics: the contract-consultant rate in effect on the registration date).
 * {@link #quarters} is the ordered x-axis; every segment's {@code series} is
 * aligned index-for-index with it.</p>
 */
public record IndustryRateTrendDTO(
        List<IndustryTrendQuarterDTO> quarters,
        List<IndustryRateSegmentDTO> industries,
        String latestFullQuarterKey
) {
    public IndustryRateTrendDTO {
        Objects.requireNonNull(quarters, "quarters");
        Objects.requireNonNull(industries, "industries");
    }
}
