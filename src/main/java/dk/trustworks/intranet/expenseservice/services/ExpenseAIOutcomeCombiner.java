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
 * </ul>
 */
public final class ExpenseAIOutcomeCombiner {

    public static final String OUTCOME_APPROVE   = "APPROVE";
    public static final String OUTCOME_SOFT_FLAG = "SOFT_FLAG";
    public static final String OUTCOME_BLOCK     = "BLOCK";

    public enum AmountSignal { NONE, SOFT, BLOCK }

    /** A rule the AI marked FAILED, with its confidence and configured tier. */
    public record FiredRule(String ruleId, double confidence, String outcomeMode, double confidenceThreshold) {}

    /** Combined result. attentionOwner/Kind are set ONLY for the amount-mismatch block path. */
    public record Outcome(String outcome, Double confidence, List<String> blockingRuleIds,
                          List<String> softFlags, String attentionOwner, String attentionKind) {}

    private ExpenseAIOutcomeCombiner() {}

    public static Outcome combine(List<FiredRule> firedRules, AmountSignal amountSignal) {
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
            soft.add("AMOUNT_MISMATCH");
        }

        if (!blocking.isEmpty()) {
            // Policy block — routed by the rule router (no pre-set owner/kind).
            return new Outcome(OUTCOME_BLOCK, maxBlockConfidence, List.copyOf(blocking),
                    List.copyOf(soft), null, null);
        }
        if (amountSignal == AmountSignal.BLOCK) {
            // Large amount mismatch — employee fixes the amount.
            return new Outcome(OUTCOME_BLOCK, null, List.of(),
                    List.copyOf(soft), "EMPLOYEE", "AMOUNT_MISMATCH");
        }
        if (!soft.isEmpty()) {
            return new Outcome(OUTCOME_SOFT_FLAG, null, List.of(), List.copyOf(soft), null, null);
        }
        return new Outcome(OUTCOME_APPROVE, null, List.of(), List.of(), null, null);
    }

    /**
     * Classify the delta between the AI-extracted receipt total and the employee-entered amount.
     * The receipt is evidence, not the data source (the employee already typed the amount), so an
     * unreadable receipt (null extracted) is NOT a signal here — it is a soft photo nudge handled
     * elsewhere. Returns NONE when there is nothing to compare.
     */
    public static AmountSignal classifyAmount(Double extracted, Double entered,
                                              double softPct, double blockPct) {
        if (extracted == null || entered == null || entered == 0.0) return AmountSignal.NONE;
        double delta = Math.abs(extracted - entered) / Math.abs(entered);
        if (delta >= blockPct) return AmountSignal.BLOCK;
        if (delta >= softPct) return AmountSignal.SOFT;
        return AmountSignal.NONE;
    }
}
