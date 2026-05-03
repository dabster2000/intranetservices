package dk.trustworks.intranet.aggregates.finance.dto.cxo;

import java.util.List;

/**
 * Wrapper response for GET /finance/cxo/revenue-by-practice.
 *
 * <p>{@link #months} is the time-ordered (ascending {@code monthKey}) series of
 * monthly data points. {@link #practices} is the ordered list of practice ids
 * that have at least one non-zero entry across the full series; the canonical
 * order is {@code [PM, SA, BA, DEV, CYB]} with {@code OTHER} appended last when
 * present.</p>
 */
public record RevenuePracticeDTO(
        List<MonthlyRevenuePracticeDataPoint> months,
        List<String> practices
) {}
