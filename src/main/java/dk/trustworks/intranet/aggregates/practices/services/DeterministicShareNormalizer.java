package dk.trustworks.intranet.aggregates.practices.services;

import java.math.BigDecimal;
import java.math.MathContext;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;

/**
 * Produces closed, deterministic {@code DECIMAL(38,18)} share vectors.
 *
 * <p>The caller owns source-specific canonicalization. In particular, duplicate source records
 * and rows which share a canonical recipient key must be rejected or merged before invoking this
 * primitive. The supplied comparator is the frozen total order for closure ties.</p>
 */
public final class DeterministicShareNormalizer {

    public static final int SHARE_SCALE = 18;
    public static final BigDecimal ONE = new BigDecimal("1.000000000000000000");
    public static final BigDecimal COMPLETE_LOWER_BOUND = new BigDecimal("0.999900000000000000");
    public static final BigDecimal COMPLETE_UPPER_BOUND = new BigDecimal("1.000100000000000000");

    private static final BigDecimal ZERO = BigDecimal.ZERO.setScale(SHARE_SCALE);
    private static final int SHARE_PRECISION = 38;

    private DeterministicShareNormalizer() {
    }

    public enum Outcome {
        COMPLETE,
        PARTIAL_WITH_RESIDUAL,
        UNUSABLE_ZERO
    }

    public enum FailureCode {
        NULL_INPUT,
        NULL_STABLE_KEY,
        NULL_SHARE,
        DUPLICATE_STABLE_KEY,
        NON_TOTAL_STABLE_ORDER,
        SHARE_OUT_OF_RANGE,
        SHARE_OVERFLOW,
        SUM_OVERFLOW,
        SUM_EXCEEDS_TOLERANCE,
        PARTIAL_NOT_PERMITTED,
        RESIDUAL_KEY_REQUIRED,
        RESIDUAL_KEY_COLLISION,
        EFFECTIVE_SHARE_OUT_OF_RANGE,
        CLOSURE_FAILURE,
        ZERO_SIGNED_CONTROL_SUM,
        SIGNED_RATIO_OVERFLOW,
        SIGNED_CLOSURE_SIGN_OPPOSITION
    }

    public static final class NormalizationException extends IllegalArgumentException {
        private final FailureCode code;

        private NormalizationException(FailureCode code) {
            super(code.name());
            this.code = code;
        }

        public FailureCode code() {
            return code;
        }
    }

    public record Candidate<K>(K stableKey, BigDecimal rawShare) {
    }

    public record NormalizedShare<K>(
            K stableKey,
            BigDecimal rawShare,
            BigDecimal effectiveShare,
            boolean closureRow,
            boolean residual,
            boolean sourceNormalizationApplied) {
    }

    public record Result<K>(
            List<NormalizedShare<K>> shares,
            BigDecimal rawShareSum,
            boolean normalizationApplied,
            Outcome outcome) {

        public Result {
            shares = List.copyOf(shares);
            Objects.requireNonNull(rawShareSum, "rawShareSum");
            Objects.requireNonNull(outcome, "outcome");
        }
    }

    public record SignedControlCandidate<K>(K stableKey, BigDecimal signedControl) {
    }

    public record NormalizedSignedRatio<K>(
            K stableKey,
            BigDecimal signedControl,
            BigDecimal signedRatio,
            boolean closureRow) {
    }

    public record SignedRatioResult<K>(
            List<NormalizedSignedRatio<K>> ratios,
            BigDecimal signedControlSum) {

        public SignedRatioResult {
            ratios = List.copyOf(ratios);
            Objects.requireNonNull(signedControlSum, "signedControlSum");
        }
    }

    public static <K extends Comparable<? super K>> Result<K> normalize(
            List<Candidate<K>> candidates,
            boolean residualPermitted,
            K unassignedKey) {
        return normalize(candidates, residualPermitted, unassignedKey, Comparator.naturalOrder());
    }

    public static <K> Result<K> normalize(
            List<Candidate<K>> candidates,
            boolean residualPermitted,
            K unassignedKey,
            Comparator<? super K> stableOrder) {
        if (candidates == null || stableOrder == null) {
            throw failure(FailureCode.NULL_INPUT);
        }

        List<PreparedCandidate<K>> prepared = prepare(candidates, stableOrder);
        BigDecimal rawShareSum = prepared.stream()
                .map(PreparedCandidate::rawShare)
                .reduce(ZERO, BigDecimal::add);
        requireRepresentable(rawShareSum, FailureCode.SUM_OVERFLOW);

        if (rawShareSum.signum() == 0) {
            List<NormalizedShare<K>> zeroShares = prepared.stream()
                    .map(candidate -> new NormalizedShare<>(
                            candidate.stableKey(), candidate.rawShare(), ZERO,
                            false, false, candidate.sourceNormalizationApplied()))
                    .toList();
            return new Result<>(zeroShares, rawShareSum, false, Outcome.UNUSABLE_ZERO);
        }

        if (rawShareSum.compareTo(COMPLETE_UPPER_BOUND) > 0) {
            throw failure(FailureCode.SUM_EXCEEDS_TOLERANCE);
        }

        if (rawShareSum.compareTo(COMPLETE_LOWER_BOUND) < 0) {
            if (!residualPermitted) {
                throw failure(FailureCode.PARTIAL_NOT_PERMITTED);
            }
            return withResidual(prepared, rawShareSum, unassignedKey, stableOrder);
        }

        return closeCompleteVector(prepared, rawShareSum);
    }

    /**
     * Closes signed document-to-item ratios. Signed controls remain exact; only ratio derivation
     * uses {@link MathContext#DECIMAL128}, followed by scale-18 {@link RoundingMode#HALF_UP}.
     */
    public static <K> SignedRatioResult<K> normalizeSignedRatios(
            List<SignedControlCandidate<K>> candidates,
            Comparator<? super K> stableOrder) {
        if (candidates == null || stableOrder == null) {
            throw failure(FailureCode.NULL_INPUT);
        }
        if (candidates.isEmpty()) {
            throw failure(FailureCode.ZERO_SIGNED_CONTROL_SUM);
        }

        List<SignedControlCandidate<K>> sorted = new ArrayList<>(candidates.size());
        Set<K> seenKeys = new HashSet<>();
        for (SignedControlCandidate<K> candidate : candidates) {
            if (candidate == null) {
                throw failure(FailureCode.NULL_INPUT);
            }
            if (candidate.stableKey() == null) {
                throw failure(FailureCode.NULL_STABLE_KEY);
            }
            if (candidate.signedControl() == null) {
                throw failure(FailureCode.NULL_SHARE);
            }
            if (!seenKeys.add(candidate.stableKey())) {
                throw failure(FailureCode.DUPLICATE_STABLE_KEY);
            }
            sorted.add(candidate);
        }
        sorted.sort((left, right) -> stableOrder.compare(left.stableKey(), right.stableKey()));
        for (int index = 1; index < sorted.size(); index++) {
            if (stableOrder.compare(sorted.get(index - 1).stableKey(), sorted.get(index).stableKey()) == 0) {
                throw failure(FailureCode.NON_TOTAL_STABLE_ORDER);
            }
        }

        BigDecimal controlSum = sorted.stream()
                .map(SignedControlCandidate::signedControl)
                .reduce(BigDecimal.ZERO, BigDecimal::add);
        if (controlSum.signum() == 0) {
            throw failure(FailureCode.ZERO_SIGNED_CONTROL_SUM);
        }

        int closureIndex = 0;
        for (int index = 1; index < sorted.size(); index++) {
            if (sorted.get(index).signedControl().abs()
                    .compareTo(sorted.get(closureIndex).signedControl().abs()) > 0) {
                closureIndex = index;
            }
        }

        List<BigDecimal> ratios = new ArrayList<>(sorted.size());
        BigDecimal nonClosureSum = ZERO;
        for (int index = 0; index < sorted.size(); index++) {
            if (index == closureIndex) {
                ratios.add(null);
                continue;
            }
            BigDecimal ratio = sorted.get(index).signedControl()
                    .divide(controlSum, MathContext.DECIMAL128)
                    .setScale(SHARE_SCALE, RoundingMode.HALF_UP);
            requireRepresentable(ratio, FailureCode.SIGNED_RATIO_OVERFLOW);
            ratios.add(ratio);
            nonClosureSum = nonClosureSum.add(ratio);
        }

        BigDecimal closure = ONE.subtract(nonClosureSum);
        requireRepresentable(closure, FailureCode.SIGNED_RATIO_OVERFLOW);
        int exactClosureSign = sorted.get(closureIndex).signedControl().signum() * controlSum.signum();
        if (exactClosureSign != 0 && closure.signum() != 0 && exactClosureSign != closure.signum()) {
            throw failure(FailureCode.SIGNED_CLOSURE_SIGN_OPPOSITION);
        }
        ratios.set(closureIndex, closure);
        if (ratios.stream().reduce(ZERO, BigDecimal::add).compareTo(ONE) != 0) {
            throw failure(FailureCode.CLOSURE_FAILURE);
        }

        List<NormalizedSignedRatio<K>> result = new ArrayList<>(sorted.size());
        for (int index = 0; index < sorted.size(); index++) {
            SignedControlCandidate<K> candidate = sorted.get(index);
            result.add(new NormalizedSignedRatio<>(
                    candidate.stableKey(), candidate.signedControl(), ratios.get(index), index == closureIndex));
        }
        return new SignedRatioResult<>(result, controlSum);
    }

    public static <K extends Comparable<? super K>> SignedRatioResult<K> normalizeSignedRatios(
            List<SignedControlCandidate<K>> candidates) {
        return normalizeSignedRatios(candidates, Comparator.naturalOrder());
    }

    private static <K> List<PreparedCandidate<K>> prepare(
            List<Candidate<K>> candidates,
            Comparator<? super K> stableOrder) {
        List<PreparedCandidate<K>> prepared = new ArrayList<>(candidates.size());
        Set<K> seenKeys = new HashSet<>();

        for (Candidate<K> candidate : candidates) {
            if (candidate == null) {
                throw failure(FailureCode.NULL_INPUT);
            }
            if (candidate.stableKey() == null) {
                throw failure(FailureCode.NULL_STABLE_KEY);
            }
            if (candidate.rawShare() == null) {
                throw failure(FailureCode.NULL_SHARE);
            }
            if (!seenKeys.add(candidate.stableKey())) {
                throw failure(FailureCode.DUPLICATE_STABLE_KEY);
            }
            if (candidate.rawShare().compareTo(BigDecimal.ZERO) < 0
                    || candidate.rawShare().compareTo(BigDecimal.ONE) > 0) {
                throw failure(FailureCode.SHARE_OUT_OF_RANGE);
            }

            BigDecimal persisted = candidate.rawShare().setScale(SHARE_SCALE, RoundingMode.HALF_UP);
            requireRepresentable(persisted, FailureCode.SHARE_OVERFLOW);
            if (persisted.compareTo(BigDecimal.ZERO) < 0 || persisted.compareTo(BigDecimal.ONE) > 0) {
                throw failure(FailureCode.SHARE_OUT_OF_RANGE);
            }
            prepared.add(new PreparedCandidate<>(
                    candidate.stableKey(),
                    persisted,
                    candidate.rawShare().compareTo(persisted) != 0));
        }

        prepared.sort((left, right) -> stableOrder.compare(left.stableKey(), right.stableKey()));
        for (int index = 1; index < prepared.size(); index++) {
            PreparedCandidate<K> previous = prepared.get(index - 1);
            PreparedCandidate<K> current = prepared.get(index);
            if (stableOrder.compare(previous.stableKey(), current.stableKey()) == 0) {
                throw failure(FailureCode.NON_TOTAL_STABLE_ORDER);
            }
        }
        return prepared;
    }

    private static <K> Result<K> closeCompleteVector(
            List<PreparedCandidate<K>> prepared,
            BigDecimal rawShareSum) {
        int closureIndex = 0;
        for (int index = 1; index < prepared.size(); index++) {
            if (prepared.get(index).rawShare().compareTo(prepared.get(closureIndex).rawShare()) > 0) {
                closureIndex = index;
            }
        }

        List<BigDecimal> effectiveShares = new ArrayList<>(prepared.size());
        BigDecimal nonClosureSum = ZERO;
        for (int index = 0; index < prepared.size(); index++) {
            if (index == closureIndex) {
                effectiveShares.add(null);
                continue;
            }
            BigDecimal effective = prepared.get(index).rawShare()
                    .divide(rawShareSum, MathContext.DECIMAL128)
                    .setScale(SHARE_SCALE, RoundingMode.HALF_UP);
            validateEffective(effective);
            effectiveShares.add(effective);
            nonClosureSum = nonClosureSum.add(effective);
            requireRepresentable(nonClosureSum, FailureCode.CLOSURE_FAILURE);
        }

        BigDecimal closure = ONE.subtract(nonClosureSum);
        validateEffective(closure);
        effectiveShares.set(closureIndex, closure);

        BigDecimal exactSum = effectiveShares.stream().reduce(ZERO, BigDecimal::add);
        if (exactSum.compareTo(ONE) != 0) {
            throw failure(FailureCode.CLOSURE_FAILURE);
        }

        List<NormalizedShare<K>> result = new ArrayList<>(prepared.size());
        boolean normalizationApplied = rawShareSum.compareTo(ONE) != 0;
        for (int index = 0; index < prepared.size(); index++) {
            PreparedCandidate<K> candidate = prepared.get(index);
            BigDecimal effective = effectiveShares.get(index);
            normalizationApplied |= candidate.rawShare().compareTo(effective) != 0;
            result.add(new NormalizedShare<>(
                    candidate.stableKey(), candidate.rawShare(), effective,
                    index == closureIndex, false, candidate.sourceNormalizationApplied()));
        }
        return new Result<>(result, rawShareSum, normalizationApplied, Outcome.COMPLETE);
    }

    private static <K> Result<K> withResidual(
            List<PreparedCandidate<K>> prepared,
            BigDecimal rawShareSum,
            K unassignedKey,
            Comparator<? super K> stableOrder) {
        if (unassignedKey == null) {
            throw failure(FailureCode.RESIDUAL_KEY_REQUIRED);
        }
        for (PreparedCandidate<K> candidate : prepared) {
            if (candidate.stableKey().equals(unassignedKey)) {
                throw failure(FailureCode.RESIDUAL_KEY_COLLISION);
            }
            if (stableOrder.compare(candidate.stableKey(), unassignedKey) == 0) {
                throw failure(FailureCode.NON_TOTAL_STABLE_ORDER);
            }
        }

        BigDecimal residual = ONE.subtract(rawShareSum);
        validateEffective(residual);
        List<NormalizedShare<K>> result = new ArrayList<>(prepared.size() + 1);
        for (PreparedCandidate<K> candidate : prepared) {
            result.add(new NormalizedShare<>(
                    candidate.stableKey(), candidate.rawShare(), candidate.rawShare(),
                    false, false, candidate.sourceNormalizationApplied()));
        }
        result.add(new NormalizedShare<>(
                unassignedKey, residual, residual, false, true, false));

        BigDecimal exactSum = result.stream()
                .map(NormalizedShare::effectiveShare)
                .reduce(ZERO, BigDecimal::add);
        if (exactSum.compareTo(ONE) != 0) {
            throw failure(FailureCode.CLOSURE_FAILURE);
        }
        return new Result<>(result, rawShareSum, false, Outcome.PARTIAL_WITH_RESIDUAL);
    }

    private static void validateEffective(BigDecimal share) {
        requireRepresentable(share, FailureCode.CLOSURE_FAILURE);
        if (share.compareTo(BigDecimal.ZERO) < 0 || share.compareTo(BigDecimal.ONE) > 0) {
            throw failure(FailureCode.EFFECTIVE_SHARE_OUT_OF_RANGE);
        }
    }

    private static void requireRepresentable(BigDecimal value, FailureCode code) {
        if (value.scale() != SHARE_SCALE || value.precision() > SHARE_PRECISION) {
            throw failure(code);
        }
    }

    private static NormalizationException failure(FailureCode code) {
        return new NormalizationException(code);
    }

    private record PreparedCandidate<K>(
            K stableKey,
            BigDecimal rawShare,
            boolean sourceNormalizationApplied) {
    }
}
