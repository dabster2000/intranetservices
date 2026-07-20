package dk.trustworks.intranet.aggregates.finance.dto.cxo;

import java.util.List;
import java.util.Objects;

/**
 * Wrapper response for GET /finance/cxo/revenue-by-practice.
 *
 * <p>{@link #months} is the time-ordered (ascending {@code monthKey}) series of
 * monthly data points. {@link #practices} is the ordered list of practice ids
 * (registry storage codes) that have at least one non-zero entry across the full
 * series; the order is the registry's active {@code type='PRACTICE'} rows by
 * {@code sort_order} with {@code OTHER} appended last when present.</p>
 *
 * <p>{@link #practiceDetails} (additive, Part 2 Phase 3) carries the registry
 * identity for each entry of {@link #practices} in the same order:
 * {@code practiceUuid} is the stable surrogate key (§4.5), {@code code} repeats
 * the storage code, {@code displayCode}/{@code name} are the registry labels.
 * The synthetic {@code OTHER} bucket has a {@code null} practiceUuid.</p>
 */
public record RevenuePracticeDTO(
        List<MonthlyRevenuePracticeDataPoint> months,
        List<String> practices,
        List<PracticeDetail> practiceDetails
) {
    public RevenuePracticeDTO {
        Objects.requireNonNull(months, "months");
        Objects.requireNonNull(practices, "practices");
        practiceDetails = practiceDetails == null ? List.of() : List.copyOf(practiceDetails);
    }

    /** Registry identity for one {@link #practices} entry (additive, Phase 3). */
    public record PracticeDetail(String practiceUuid, String code, String displayCode, String name) {
    }
}
