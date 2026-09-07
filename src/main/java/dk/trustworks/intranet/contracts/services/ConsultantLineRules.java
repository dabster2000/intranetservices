package dk.trustworks.intranet.contracts.services;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;

/**
 * Pure rules for a consultant line (JK Team 2.0 WP5 / WP4b), applied by
 * {@link ContractValidationService} before a line is written and mirrored by the contract
 * editor client-side.
 *
 * <ul>
 *   <li><b>Pricing model</b>: required when the consultant is hourly paid during any part
 *       of the assignment; when given it must be an active {@code pricing_model_definitions.code}.</li>
 *   <li><b>Zero rate</b> (WP4b, spec §4.4.1): a rate of 0 is legal only when declared —
 *       reason, review date <em>and</em> list rate — mirroring the
 *       {@code chk_consultant_rate_declared} CHECK. An accidental zero still fails, with
 *       every missing field named in one message.</li>
 * </ul>
 */
public final class ConsultantLineRules {

    /** {@code zero_rate_reason} values; {@code PILOT_FREE} pairs with the Model 3 pricing model. */
    public static final Set<String> ZERO_RATE_REASONS = Set.of("PILOT_FREE", "GOODWILL", "INTERNAL_TRANSFER", "OTHER");

    private ConsultantLineRules() {
    }

    /** {@code null} when the model is acceptable, otherwise the problem. */
    public static String pricingModelProblem(String pricingModelCode, Set<String> activeCodes) {
        return pricingModelProblem(pricingModelCode, activeCodes, false);
    }

    public static String pricingModelProblem(String pricingModelCode, Set<String> activeCodes, boolean required) {
        if (pricingModelCode == null || pricingModelCode.isBlank()) {
            return required ? "Pricing model is required for consultants paid by the hour during the assignment" : null;
        }
        if (activeCodes == null || !activeCodes.contains(pricingModelCode.trim())) {
            return "Unknown or inactive pricing model '" + pricingModelCode + "'";
        }
        return null;
    }

    /**
     * Problems with the rate declaration; empty when the line is acceptable.
     *
     * @param rate           the hourly rate
     * @param zeroRateReason {@link #ZERO_RATE_REASONS} or {@code null}
     * @param rateReviewDate the hard price-rise deadline, or {@code null}
     * @param listRate       the notional rate the hours are worth, or {@code null}
     */
    public static List<String> zeroRateProblems(double rate, String zeroRateReason, LocalDate rateReviewDate,
                                                Double listRate) {
        List<String> problems = new ArrayList<>();
        if (rate < 0) {
            problems.add("Rate must not be negative");
            return problems;
        }
        if (rate > 0) {
            // A positive rate carries no declaration; stray values are refused rather than
            // silently kept, so a later step-up cannot inherit a stale reason.
            if (zeroRateReason != null && !zeroRateReason.isBlank()) {
                problems.add("A zero-rate reason only applies to a rate of 0");
            }
            return problems;
        }
        // rate == 0: must be declared, naming every missing piece (spec §4.4.6).
        List<String> missing = new ArrayList<>();
        if (zeroRateReason == null || zeroRateReason.isBlank()) {
            missing.add("a zero-rate reason");
        } else if (!ZERO_RATE_REASONS.contains(zeroRateReason.trim())) {
            problems.add("Unknown zero-rate reason '" + zeroRateReason + "' — use one of " + String.join(", ", ZERO_RATE_REASONS));
        }
        if (rateReviewDate == null) {
            missing.add("a rate review date");
        }
        if (listRate == null) {
            missing.add("a list rate");
        } else if (listRate <= 0) {
            problems.add("The list rate must be greater than 0");
        }
        if (!missing.isEmpty()) {
            problems.add("A rate of 0 must be declared with " + String.join(", ", missing));
        }
        return problems;
    }
}
