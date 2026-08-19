package dk.trustworks.intranet.expenseservice.services;

import java.util.ArrayList;
import java.util.List;

/**
 * Pure combination of per-rule AI verdicts + an amount-mismatch signal into one of three
 * outcomes (APPROVE / SOFT_FLAG / BLOCK). Framework-free and fully unit-tested — this is the
 * local TDD gate for the AI tier logic. Mirrors {@code ExpenseStateDeriver} in style.
 *
 * <p>Rules:
 * <ul>
 *   <li>OFF rules are ignored.</li>
 *   <li>SOFT_FLAG rules add a soft flag, never block.</li>
 *   <li>BLOCK rules block iff confidence ≥ threshold; otherwise they demote to a soft flag.</li>
 *   <li>A large amount mismatch (BLOCK signal) blocks as EMPLOYEE/AMOUNT_MISMATCH, but only
 *       when no policy rule already blocks (a policy block routes via the rule router).</li>
 *   <li>A small amount mismatch (SOFT signal) adds an AMOUNT_MISMATCH soft flag.</li>
 *   <li>When an amount signal fires on an amount the employee never touched (accepted straight
 *       from the pre-submit AI pre-fill), an extra AMOUNT_PREFILL_UNMODIFIED flag records that
 *       no human ever read that number. Routing is unchanged — the flag is evidence only.</li>
 *   <li>A foreign-currency receipt makes the amount delta uncomparable; the caller downgrades the
 *       signal and adds AMOUNT_CURRENCY_UNCOMPARABLE — see {@link #isCurrencyComparable}.</li>
 * </ul>
 */
public final class ExpenseAIOutcomeCombiner {

    public static final String OUTCOME_APPROVE   = "APPROVE";
    public static final String OUTCOME_SOFT_FLAG = "SOFT_FLAG";
    public static final String OUTCOME_BLOCK     = "BLOCK";

    /** Soft flag: the two independent reads of the receipt total disagree. */
    public static final String FLAG_AMOUNT_MISMATCH = "AMOUNT_MISMATCH";
    /**
     * Soft flag: the submitted amount is the AI pre-fill verbatim — no human ever verified it.
     *
     * <p><b>Not surfaced in any UI.</b> The expense frontend renders only {@code softFlags.length}
     * and has no label for this value, so it exists purely as a stored marker on the row: it is
     * written for offline DB analysis and spot-check sampling (query {@code expenses} /
     * the AI outcome rows for it), never for an employee or approver to read on screen.
     */
    public static final String FLAG_AMOUNT_PREFILL_UNMODIFIED = "AMOUNT_PREFILL_UNMODIFIED";
    /**
     * Soft flag: the receipt total and the submitted amount are in different currencies, so the
     * delta between them measures an exchange rate, not an error. Raised instead of an amount
     * BLOCK — see {@code ExpenseAIValidationService.normalizePolicyVerdict}.
     */
    public static final String FLAG_AMOUNT_CURRENCY_UNCOMPARABLE = "AMOUNT_CURRENCY_UNCOMPARABLE";

    /** Largest absolute difference (DKK) still counted as "the same number". Half an øre. */
    private static final double AMOUNT_EQUALITY_EPSILON = 0.005;

    public enum AmountSignal { NONE, SOFT, BLOCK }

    /**
     * Where the submitted amount came from. The pre-submit vision pass pre-fills the amount
     * field for the employee, so an unmodified pre-fill is a machine reading, not a human one:
     * the post-submit comparison is then the only check that number ever receives.
     */
    public enum AmountProvenance { AI_PREFILL_UNMODIFIED, HUMAN_ENTERED, UNKNOWN }

    /** A rule the AI marked FAILED, with its confidence and configured tier. */
    public record FiredRule(String ruleId, double confidence, String outcomeMode, double confidenceThreshold) {}

    /** Combined result. attentionOwner/Kind are set ONLY for the amount-mismatch block path. */
    public record Outcome(String outcome, Double confidence, List<String> blockingRuleIds,
                          List<String> softFlags, String attentionOwner, String attentionKind) {}

    private ExpenseAIOutcomeCombiner() {}

    /** Back-compat overload: combine without provenance evidence about the entered amount. */
    public static Outcome combine(List<FiredRule> firedRules, AmountSignal amountSignal) {
        return combine(firedRules, amountSignal, AmountProvenance.UNKNOWN);
    }

    public static Outcome combine(List<FiredRule> firedRules, AmountSignal amountSignal,
                                  AmountProvenance amountProvenance) {
        List<String> blocking = new ArrayList<>();
        List<String> soft = new ArrayList<>();
        double maxBlockConfidence = 0.0;

        for (FiredRule r : firedRules) {
            String mode = r.outcomeMode() == null ? "BLOCK" : r.outcomeMode();
            switch (mode) {
                case OUTCOME_BLOCK -> {
                    if (r.confidence() >= r.confidenceThreshold()) {
                        blocking.add(r.ruleId());
                        if (r.confidence() > maxBlockConfidence) maxBlockConfidence = r.confidence();
                    } else {
                        soft.add(r.ruleId());
                    }
                }
                case OUTCOME_SOFT_FLAG -> soft.add(r.ruleId());
                default -> { /* OFF: ignore */ }
            }
        }

        // Amount-mismatch signal layers on top.
        if (amountSignal == AmountSignal.SOFT) {
            soft.add(FLAG_AMOUNT_MISMATCH);
        }
        // Provenance is recorded only where it changes how the mismatch reads: an amount the
        // employee never touched has no independent human reading behind it. Deliberately not
        // emitted on clean rows — that would soft-flag most of the population and pull it into
        // the weekly spot-check sample, which is an owner policy call, not a bug fix.
        if (amountSignal != AmountSignal.NONE && amountProvenance == AmountProvenance.AI_PREFILL_UNMODIFIED) {
            soft.add(FLAG_AMOUNT_PREFILL_UNMODIFIED);
        }

        if (!blocking.isEmpty()) {
            // Policy block — routed by the rule router (no pre-set owner/kind).
            return new Outcome(OUTCOME_BLOCK, maxBlockConfidence, List.copyOf(blocking),
                    List.copyOf(soft), null, null);
        }
        if (amountSignal == AmountSignal.BLOCK) {
            // Large amount mismatch — employee fixes the amount.
            return new Outcome(OUTCOME_BLOCK, null, List.of(),
                    List.copyOf(soft), "EMPLOYEE", FLAG_AMOUNT_MISMATCH);
        }
        if (!soft.isEmpty()) {
            return new Outcome(OUTCOME_SOFT_FLAG, null, List.of(), List.copyOf(soft), null, null);
        }
        return new Outcome(OUTCOME_APPROVE, null, List.of(), List.of(), null, null);
    }

    /**
     * Classify the delta between the AI-extracted receipt total and the submitted amount.
     * An unreadable receipt (null extracted) is NOT a signal here — it is a soft photo nudge
     * handled elsewhere. Returns NONE when there is nothing to compare.
     *
     * <p>This is only a guard if {@code extracted} was read without sight of {@code entered}.
     * The submitted amount is frequently the pre-submit vision pass's own reading (the wizard
     * pre-fills the field), so the post-submit read must be taken blind — see
     * {@code ExpenseAIValidationService.buildValidationContext}, which deliberately withholds
     * the amount from the policy call — and {@link #classifyProvenance} records whether a human
     * ever touched the number.
     */
    public static AmountSignal classifyAmount(Double extracted, Double entered,
                                              double softPct, double blockPct) {
        if (extracted == null || entered == null || entered == 0.0) return AmountSignal.NONE;
        double delta = Math.abs(extracted - entered) / Math.abs(entered);
        if (delta >= blockPct) return AmountSignal.BLOCK;
        if (delta >= softPct) return AmountSignal.SOFT;
        return AmountSignal.NONE;
    }

    /**
     * Classify where the submitted amount came from, by comparing it against the amount the
     * pre-submit pass offered as a pre-fill.
     *
     * <p>A {@code null} pre-fill means nothing was ever offered (no analysis, or the vision pass
     * could not read a total), so the number can only be the employee's own. Re-typing the
     * offered value by hand is indistinguishable from accepting it and is deliberately counted
     * as an unmodified pre-fill — the conservative reading.
     *
     * @param prefilledAmount amount the pre-submit pass pre-filled, or {@code null} if none
     * @param enteredAmount   amount actually submitted
     */
    /**
     * True when the receipt total may be compared numerically against the submitted amount.
     *
     * <p>{@code expense.amount} is a DKK field, while the receipt total is whatever the receipt
     * printed: the pre-submit pass is explicitly instructed never to convert to DKK. An employee
     * who correctly converts a 100 EUR receipt to 745 DKK therefore produces a ~7x delta that is
     * an exchange rate, not a mistake. An unknown currency (null/blank — nothing recorded) is
     * treated as comparable, which is the status quo for every row without a pre-submit analysis.
     *
     * @param receiptCurrency ISO code recorded by the pre-submit pass, or {@code null} if unknown
     */
    public static boolean isCurrencyComparable(String receiptCurrency) {
        return receiptCurrency == null
                || receiptCurrency.isBlank()
                || "DKK".equalsIgnoreCase(receiptCurrency.trim());
    }

    public static AmountProvenance classifyProvenance(Double prefilledAmount, Double enteredAmount) {
        if (enteredAmount == null) return AmountProvenance.UNKNOWN;
        if (prefilledAmount == null) return AmountProvenance.HUMAN_ENTERED;
        return Math.abs(prefilledAmount - enteredAmount) <= AMOUNT_EQUALITY_EPSILON
                ? AmountProvenance.AI_PREFILL_UNMODIFIED
                : AmountProvenance.HUMAN_ENTERED;
    }
}
