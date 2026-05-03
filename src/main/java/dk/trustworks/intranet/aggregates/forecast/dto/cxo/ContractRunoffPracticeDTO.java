package dk.trustworks.intranet.aggregates.forecast.dto.cxo;

/**
 * One per-practice slice within a {@link ContractRunoffMonthDTO}, returned by
 * GET /forecast/cxo/contract-runoff.
 *
 * <p>Mirrors the inner array element of the {@code ContractRunoffMonthDTO}
 * TypeScript contract in {@code src/lib/types/cxo.ts}. Each row corresponds to
 * one (future_month, practice, is_extension, is_expired) tuple from
 * {@code fact_revenue_runoff} — one month may emit multiple practice slices,
 * and the same practice may appear more than once in a month when split
 * between expiring and active or extension and new.</p>
 *
 * <p>{@code isExpiring} reflects {@code is_expired = 1} from the fact row; the
 * field name follows the TS contract.</p>
 */
public record ContractRunoffPracticeDTO(
        String practice,
        double revenueDkk,
        boolean isExpiring
) {}
