package dk.trustworks.intranet.aggregates.finance.dto.cxo;

import java.util.List;
import java.util.Objects;

/**
 * Wrapper response for GET /clients/cxo/new-vs-repeat-revenue.
 *
 * <p>{@link #quarters} is the time-ordered (ascending year then quarter) series
 * of quarterly buckets, each split into NEW vs REPEAT revenue. A project's
 * revenue counts as NEW for the quarter that contains the project's first-ever
 * invoice; otherwise the same project's revenue is REPEAT.</p>
 */
public record NewVsRepeatClientRevenueDTO(
        List<QuarterlyNewVsRepeatDTO> quarters
) {
    public NewVsRepeatClientRevenueDTO {
        Objects.requireNonNull(quarters, "quarters");
    }
}
