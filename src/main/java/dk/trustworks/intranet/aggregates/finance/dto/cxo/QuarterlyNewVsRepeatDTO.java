package dk.trustworks.intranet.aggregates.finance.dto.cxo;

/**
 * One quarter row in the New vs Repeat client revenue series.
 *
 * <p>{@code repeatSharePercent} is boxed because total revenue can be zero in
 * empty quarters; the BFF contract returns {@code null} in that case rather
 * than {@code 0.0} so the UI can render an N/A indicator.</p>
 *
 * <p>Field names match the frontend {@code QuarterlyNewVsRepeatDTO} (camelCase),
 * so no {@code @JsonProperty} annotations are needed.</p>
 */
public record QuarterlyNewVsRepeatDTO(
        int year,
        int quarter,
        String quarterLabel,
        double newRevenueDkk,
        double repeatRevenueDkk,
        double totalRevenueDkk,
        Double repeatSharePercent
) {
    public QuarterlyNewVsRepeatDTO {
        if (quarter < 1 || quarter > 4)
            throw new IllegalArgumentException("quarter out of range: " + quarter);
        if (year < 2000 || year > 2100)
            throw new IllegalArgumentException("year out of range: " + year);
    }
}
