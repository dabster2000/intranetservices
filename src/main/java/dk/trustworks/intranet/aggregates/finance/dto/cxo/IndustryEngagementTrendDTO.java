package dk.trustworks.intranet.aggregates.finance.dto.cxo;

import java.util.List;
import java.util.Objects;

/**
 * Wrapper response for GET /clients/cxo/industry-engagement-trend.
 *
 * <p>Average consultant-engagement length per industry segment for the trailing
 * 12 full calendar quarters, derived from registered work rather than contract
 * dates. An engagement is one consultant working at one client: months with at
 * least {@link #activeMonthMinHours} billable hours count as active, silent
 * stretches of up to {@link #bridgeMonths} months (vacation, sick leave, short
 * bench time) are bridged, and a longer silence ends the engagement.
 * {@link #quarters} is the ordered x-axis; every segment's {@code series} is
 * aligned index-for-index with it.</p>
 */
public record IndustryEngagementTrendDTO(
        List<IndustryTrendQuarterDTO> quarters,
        List<IndustryEngagementSegmentDTO> industries,
        String latestFullQuarterKey,
        double activeMonthMinHours,
        int bridgeMonths
) {
    public IndustryEngagementTrendDTO {
        Objects.requireNonNull(quarters, "quarters");
        Objects.requireNonNull(industries, "industries");
    }
}
