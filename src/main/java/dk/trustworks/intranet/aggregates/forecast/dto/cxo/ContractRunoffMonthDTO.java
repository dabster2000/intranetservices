package dk.trustworks.intranet.aggregates.forecast.dto.cxo;

import java.util.List;
import java.util.Objects;

/**
 * One future month in the contract runoff curve returned by
 * GET /forecast/cxo/contract-runoff.
 *
 * <p>Mirrors the {@code ContractRunoffMonthDTO} TypeScript contract in
 * {@code src/lib/types/cxo.ts}. Source rows come from
 * {@code fact_revenue_runoff} grouped by future_month, practice, is_extension,
 * is_expired; aggregation by month happens in the service layer (mirroring the
 * BFF route's Java-side aggregation).</p>
 *
 * <p>The field name is {@code month} (YYYYMM) — matching the TS contract; the
 * compact constructor enforces the format. {@code monthLabel} is computed
 * server-side via {@code Month.getDisplayName(SHORT, ENGLISH)} so the UI does
 * not need a month-name lookup table. {@code byPractice} is defensively
 * copied to keep the record immutable.</p>
 */
public record ContractRunoffMonthDTO(
        String month,
        String monthLabel,
        double activeRevenueDkk,
        double expiringRevenueDkk,
        long expiringContractCount,
        List<ContractRunoffPracticeDTO> byPractice,
        double newRevenueDkk,
        double extensionRevenueDkk
) {
    public ContractRunoffMonthDTO {
        if (month == null || !month.matches("\\d{6}"))
            throw new IllegalArgumentException("month must be YYYYMM, was " + month);
        Objects.requireNonNull(monthLabel, "monthLabel");
        byPractice = byPractice == null ? List.of() : List.copyOf(byPractice);
    }
}
