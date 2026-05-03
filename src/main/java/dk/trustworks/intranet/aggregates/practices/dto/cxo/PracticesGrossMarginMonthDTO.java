package dk.trustworks.intranet.aggregates.practices.dto.cxo;

/**
 * One practice's TTM gross-margin row returned by GET /practices/cxo/gross-margin.
 *
 * <p>Mirrors the {@code PracticeGrossMarginDTO} TypeScript contract in
 * {@code src/lib/types/cxo.ts}. {@code practiceId} is one of
 * {@code PM | BA | CYB | DEV | SA}. The five practices are always returned in
 * fixed order regardless of which practices have rows, so the chart renderer
 * can rely on the shape.</p>
 *
 * <p>TTM window: 12 complete calendar months ending at month {@code today−1}
 * (current month + most-recent-complete-month are excluded as a lag buffer).
 * Prior window: the 12-month window immediately preceding the TTM window.</p>
 *
 * <p>Revenue is computed at consultant-level from {@code invoiceitems} joined
 * to {@code invoices}, {@code user.practice}, {@code userstatus} (consultant
 * status at invoice date), and the {@code currences} table (currency
 * conversion to DKK). Cost is OPEX + GL Salaries from {@code fact_opex_mat}
 * filtered to {@code cost_type IN ('OPEX', 'SALARIES')}.</p>
 *
 * <p>Margin %s are nullable (Double) — {@code null} when the period revenue is
 * zero (avoids division-by-zero). {@code marginDeltaPts} is also nullable:
 * {@code null} when either side of the delta is null. Non-null margins are
 * percentage points (e.g. 35.5 = 35.5%).</p>
 */
public record PracticesGrossMarginMonthDTO(
        String practiceId,
        double currentRevenueDkk,
        double currentCostDkk,
        Double currentMarginPct,
        double priorRevenueDkk,
        double priorCostDkk,
        Double priorMarginPct,
        Double marginDeltaPts
) {
    public PracticesGrossMarginMonthDTO {
        if (practiceId == null || practiceId.isBlank())
            throw new IllegalArgumentException("practiceId must not be null/blank");
        if (!Double.isFinite(currentRevenueDkk))
            throw new IllegalArgumentException("currentRevenueDkk must be finite: " + currentRevenueDkk);
        if (!Double.isFinite(currentCostDkk))
            throw new IllegalArgumentException("currentCostDkk must be finite: " + currentCostDkk);
        if (!Double.isFinite(priorRevenueDkk))
            throw new IllegalArgumentException("priorRevenueDkk must be finite: " + priorRevenueDkk);
        if (!Double.isFinite(priorCostDkk))
            throw new IllegalArgumentException("priorCostDkk must be finite: " + priorCostDkk);
        if (currentMarginPct != null && !Double.isFinite(currentMarginPct))
            throw new IllegalArgumentException("currentMarginPct must be finite or null: " + currentMarginPct);
        if (priorMarginPct != null && !Double.isFinite(priorMarginPct))
            throw new IllegalArgumentException("priorMarginPct must be finite or null: " + priorMarginPct);
        if (marginDeltaPts != null && !Double.isFinite(marginDeltaPts))
            throw new IllegalArgumentException("marginDeltaPts must be finite or null: " + marginDeltaPts);
    }
}
