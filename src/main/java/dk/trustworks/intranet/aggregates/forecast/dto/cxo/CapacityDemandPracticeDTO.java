package dk.trustworks.intranet.aggregates.forecast.dto.cxo;

import java.util.Objects;

/**
 * Per-practice slice of the capacity-vs-demand view returned inside
 * {@link CapacityDemandMonthDTO#byPractice()}.
 *
 * <p>Mirrors the {@code CapacityDemandPracticeDTO} TypeScript contract in
 * {@code src/lib/types/cxo.ts}. The {@code practice} key is taken from
 * {@code practice_id} in {@code fact_employee_monthly_mat} (capacity side)
 * and from {@code service_line_id} in {@code fact_backlog} /
 * {@code fact_pipeline} (demand side); a NULL on either side falls back to
 * {@code "Unknown"}. All FTE values are rounded to 1 decimal place
 * server-side (BFF parity).</p>
 */
public record CapacityDemandPracticeDTO(
        String practice,
        double capacityFte,
        double demandFte,
        double gapFte
) {
    public CapacityDemandPracticeDTO {
        Objects.requireNonNull(practice, "practice");
        if (!Double.isFinite(gapFte))
            throw new IllegalArgumentException("gapFte must be finite, was " + gapFte);
    }
}
