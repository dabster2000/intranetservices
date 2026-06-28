package dk.trustworks.intranet.financeservice.model.enums;

/**
 * Revenue/cost recognition basis for the Executive Dashboard.
 *
 * <ul>
 *   <li>{@link #INVOICED} — the default. Revenue is recognised by the invoice
 *       date ({@code fact_company_revenue_mat}) and the matching internal cost by
 *       the issuer's invoicedate / GL expensedate. This basis reconciles to
 *       e-conomic to the krone and must remain byte-identical to the historical
 *       behaviour.</li>
 *   <li>{@link #WORK_PERIOD} — an opt-in management view. The SAME invoices are
 *       recognised by their WORK/SERVICE period ({@code invoices.year/month},
 *       via {@code fact_company_revenue_workperiod}), and the matching
 *       invoice-derived internal cost (QUEUED + CREATED synth) is re-timed onto
 *       that same work month. Salaries, OPEX and GL subcontractor direct cost
 *       stay on the month incurred (they already align to the work month and
 *       carry no work-period field). This basis deliberately diverges from
 *       e-conomic month-by-month; the most recent months understate until the
 *       work is billed.</li>
 * </ul>
 */
public enum RevenueBasis {
    INVOICED,
    WORK_PERIOD;

    /**
     * Parse the {@code basis} query parameter. Null / blank / unrecognised
     * values fall back to {@link #INVOICED} so the default never regresses.
     */
    public static RevenueBasis fromQueryParam(String value) {
        if (value == null || value.isBlank()) {
            return INVOICED;
        }
        try {
            return RevenueBasis.valueOf(value.trim().toUpperCase());
        } catch (IllegalArgumentException ignored) {
            return INVOICED;
        }
    }
}
