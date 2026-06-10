package dk.trustworks.intranet.aggregates.invoice.selfbilled.services;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;

/**
 * Ranks (consultant, work-period) suggestions for one unassigned voucher line.
 *
 * <p>Deliberately PURE: no DB, no CDI annotations, no Quarkus imports. The
 * assignment service (Phase 3, Task 9) composes the inputs and calls
 * {@link #suggest}. Never commits an assignment — a human (or the same-company
 * bulk accept when {@link #HIGH_CONFIDENCE} is reached) disposes.
 *
 * <p>Signals applied in priority order (spec §5.1):
 * <ol>
 *   <li>Parsed line text carries period + code → primary signal.
 *   <li>Code map resolves the code to a consultant UUID (base 90 pts).
 *   <li>Amount-match to registered work in the suggested period (+5 pts → 95).
 *   <li>Prior human assignment for the same code, when code map misses (70 pts).
 *   <li>Unique amount-match across all consultants, no code context (50 pts).
 * </ol>
 */
public final class AssignmentSuggester {

    private AssignmentSuggester() {}

    /** Bulk same-company accept threshold (AC2). Suggestions at or above this are auto-acceptable. */
    public static final int HIGH_CONFIDENCE = 90;

    /** ±1 kr tolerance for registered-work vs voucher-net comparison (covers rounding in work-value calc). */
    private static final BigDecimal WORK_MATCH_TOLERANCE = BigDecimal.ONE;

    // ------------------------------------------------------------------------------------------
    // Input / Output records
    // ------------------------------------------------------------------------------------------

    /**
     * All inputs that {@link #suggest} may need. Assembled by the caller — no DB access here.
     * Both maps ({@code workValueByConsultant}, {@code priorConsultantByCode}) must be
     * non-null (possibly empty).
     *
     * @param suggestedCode          voucher-resolved consultant code from the line text (null when unparseable)
     * @param workYear               parsed work period year from the line text (null when unparseable)
     * @param workMonth              parsed work period month (1-12) from the line text (null when unparseable)
     * @param mappedConsultantUuid   code-map resolution of {@code suggestedCode} (null when unmapped or code absent)
     * @param normalizedNet          voucher net amount, normalised to positive (e.g. abs of self-billed amount)
     * @param workValueByConsultant  registered work value (positive, in DKK) per consultant UUID for the suggested
     *                               period — obtained via work→task→project→client join; raw {@code work} table
     *                               is NOT self-sufficient
     * @param priorConsultantByCode  most recent confirmed human assignment per code (signal 4 fallback)
     */
    public record SuggesterInput(
            String suggestedCode,
            Integer workYear,
            Integer workMonth,
            String mappedConsultantUuid,
            BigDecimal normalizedNet,
            Map<String, BigDecimal> workValueByConsultant,
            Map<String, String> priorConsultantByCode) {}

    /**
     * A ranked suggestion for one voucher line.
     *
     * @param consultantUuid UUID of the suggested consultant
     * @param workYear       suggested work period year
     * @param workMonth      suggested work period month (1-12)
     * @param confidence     0–100; {@link #HIGH_CONFIDENCE} gates bulk accept
     * @param reason         human-readable explanation (shown in workbench UI)
     */
    public record Suggestion(
            String consultantUuid,
            int workYear,
            int workMonth,
            int confidence,
            String reason) {}

    // ------------------------------------------------------------------------------------------
    // Core algorithm
    // ------------------------------------------------------------------------------------------

    /**
     * Returns an ordered list of suggestions (highest confidence first). An empty list means
     * the line requires manual human assignment.
     */
    public static List<Suggestion> suggest(SuggesterInput in) {
        // Without a parsed work period there is no safe suggestion — human-only.
        if (in.workYear() == null || in.workMonth() == null) {
            return List.of();
        }

        int y = in.workYear();
        int m = in.workMonth();

        // Signal 1+2: code map hit (base 90 pts).
        if (in.mappedConsultantUuid() != null) {
            boolean workMatch = matches(in.workValueByConsultant().get(in.mappedConsultantUuid()), in.normalizedNet());
            return List.of(new Suggestion(
                    in.mappedConsultantUuid(), y, m,
                    workMatch ? 95 : 90,
                    workMatch
                            ? "code map + period from line text + registered work matches"
                            : "code map + period from line text"));
        }

        // Signal 4: prior human assignment for this code (fallback when code map misses).
        // Single get + null check: a null-valued map entry must never produce a Suggestion
        // with a null consultantUuid — it falls through to signal 3 instead.
        if (in.suggestedCode() != null) {
            String prior = in.priorConsultantByCode().get(in.suggestedCode());
            if (prior != null) {
                return List.of(new Suggestion(
                        prior, y, m,
                        70,
                        "prior assignment for code " + in.suggestedCode()));
            }
        }

        // Signal 3 standalone: exactly ONE consultant's registered work is within tolerance.
        String uniqueMatch = null;
        for (Map.Entry<String, BigDecimal> e : in.workValueByConsultant().entrySet()) {
            if (matches(e.getValue(), in.normalizedNet())) {
                if (uniqueMatch != null) {
                    // Two or more consultants match → ambiguous, return nothing.
                    return List.of();
                }
                uniqueMatch = e.getKey();
            }
        }
        if (uniqueMatch != null) {
            return List.of(new Suggestion(uniqueMatch, y, m, 50, "amount matches registered work"));
        }

        return List.of();
    }

    // ------------------------------------------------------------------------------------------
    // Helpers
    // ------------------------------------------------------------------------------------------

    /** True when {@code workValue} is within {@link #WORK_MATCH_TOLERANCE} of {@code normalizedNet}. */
    private static boolean matches(BigDecimal workValue, BigDecimal normalizedNet) {
        return workValue != null
                && normalizedNet != null
                && workValue.subtract(normalizedNet).abs().compareTo(WORK_MATCH_TOLERANCE) <= 0;
    }
}
