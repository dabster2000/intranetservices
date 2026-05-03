package dk.trustworks.intranet.aggregates.forecast.dto.cxo;

import java.util.List;
import java.util.Objects;

/**
 * One month in the capacity-vs-demand view returned by
 * GET /forecast/cxo/capacity-demand.
 *
 * <p>Mirrors the {@code CapacityDemandMonthDTO} TypeScript contract in
 * {@code src/lib/types/cxo.ts}. The window is the current month plus 12
 * forward months — generated in Java by walking a monthly cursor. Every
 * month appears exactly once (13 total) even when no row in the underlying
 * tables matches.</p>
 *
 * <p>FTE conversion: {@code revenueDkk / (1200 * 160.33)} where 1200 is the
 * default hourly rate and 160.33 is average hours per month. All FTE values
 * (totals and per-practice) are rounded to 1 decimal place server-side
 * (BFF parity).</p>
 */
public record CapacityDemandMonthDTO(
        String month,
        String monthLabel,
        double capacityFte,
        double backlogDemandFte,
        double pipelineDemandFte,
        double totalDemandFte,
        double gapFte,
        List<CapacityDemandPracticeDTO> byPractice
) {
    public CapacityDemandMonthDTO {
        if (month == null || !month.matches("\\d{6}"))
            throw new IllegalArgumentException("month must be YYYYMM, was " + month);
        Objects.requireNonNull(monthLabel, "monthLabel");
        byPractice = byPractice == null ? List.of() : List.copyOf(byPractice);
    }
}
