package dk.trustworks.intranet.aggregates.practices.services;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;

/**
 * Deterministically apportions a signed cent control over exact unrounded amounts.
 *
 * <p>Every row is floored mathematically to cents. Remaining cents are awarded one per row in
 * descending fractional-cent order, with the caller's frozen total order breaking equal-residue
 * ties. This class never uses per-row half-up rounding or a multi-cent remainder sink.</p>
 */
public final class BalancedCentAllocator {

    private static final int MONEY_SCALE = 2;
    private static final BigDecimal CENT = new BigDecimal("0.01");
    private static final BigDecimal ZERO_MONEY = BigDecimal.ZERO.setScale(MONEY_SCALE);

    private BalancedCentAllocator() {
    }

    public enum TargetMode {
        AUTHORITATIVE,
        ONCE_ROUNDED_PROVISIONAL
    }

    public enum FailureCode {
        NULL_INPUT,
        EMPTY_VECTOR,
        NULL_STABLE_KEY,
        NULL_AMOUNT,
        DUPLICATE_STABLE_KEY,
        NON_TOTAL_STABLE_ORDER,
        TARGET_NOT_CENT_CONTROL,
        TARGET_MISMATCH,
        INVALID_FRACTIONAL_CENT,
        REMAINING_CENTS_NOT_INTEGER,
        REMAINING_CENTS_OUT_OF_RANGE,
        SIGN_REVERSAL,
        NON_CONSERVING_RESULT
    }

    public static final class AllocationException extends IllegalArgumentException {
        private final FailureCode code;

        private AllocationException(FailureCode code) {
            super(code.name());
            this.code = code;
        }

        public FailureCode code() {
            return code;
        }
    }

    public record Candidate<K>(K stableKey, BigDecimal unroundedAmount) {
    }

    public record Allocation<K>(
            K stableKey,
            BigDecimal unroundedAmount,
            BigDecimal floorAmount,
            BigDecimal fractionalCent,
            boolean centAwarded,
            BigDecimal roundedAmount) {
    }

    public record Result<K>(
            List<Allocation<K>> allocations,
            BigDecimal targetControl,
            BigDecimal exactUnroundedSum,
            int remainingCentCount,
            TargetMode targetMode) {

        public Result {
            allocations = List.copyOf(allocations);
            Objects.requireNonNull(targetControl, "targetControl");
            Objects.requireNonNull(exactUnroundedSum, "exactUnroundedSum");
            Objects.requireNonNull(targetMode, "targetMode");
        }
    }

    public static <K extends Comparable<? super K>> Result<K> allocate(
            BigDecimal targetControl,
            List<Candidate<K>> candidates,
            TargetMode targetMode) {
        return allocate(targetControl, candidates, Comparator.naturalOrder(), targetMode);
    }

    public static <K> Result<K> allocate(
            BigDecimal targetControl,
            List<Candidate<K>> candidates,
            Comparator<? super K> stableOrder,
            TargetMode targetMode) {
        if (targetControl == null || candidates == null || stableOrder == null || targetMode == null) {
            throw failure(FailureCode.NULL_INPUT);
        }
        if (candidates.isEmpty()) {
            throw failure(FailureCode.EMPTY_VECTOR);
        }

        BigDecimal centTarget = requireCentControl(targetControl);
        List<PreparedCandidate<K>> prepared = prepare(candidates, stableOrder);
        BigDecimal exactSum = prepared.stream()
                .map(PreparedCandidate::unroundedAmount)
                .reduce(BigDecimal.ZERO, BigDecimal::add);
        validateTarget(centTarget, exactSum, targetMode);

        BigDecimal floorSum = prepared.stream()
                .map(PreparedCandidate::floorAmount)
                .reduce(ZERO_MONEY, BigDecimal::add);
        int remainingCentCount = remainingCents(centTarget, floorSum);
        int maximum = targetMode == TargetMode.AUTHORITATIVE
                ? prepared.size() - 1
                : prepared.size();
        if (remainingCentCount < 0 || remainingCentCount > maximum) {
            throw failure(FailureCode.REMAINING_CENTS_OUT_OF_RANGE);
        }

        List<PreparedCandidate<K>> awardOrder = new ArrayList<>(prepared);
        awardOrder.sort((left, right) -> {
            int residueOrder = right.fractionalCent().compareTo(left.fractionalCent());
            return residueOrder != 0
                    ? residueOrder
                    : stableOrder.compare(left.stableKey(), right.stableKey());
        });
        Set<K> awardedKeys = new HashSet<>();
        for (int index = 0; index < remainingCentCount; index++) {
            awardedKeys.add(awardOrder.get(index).stableKey());
        }

        List<Allocation<K>> allocations = new ArrayList<>(prepared.size());
        BigDecimal roundedSum = ZERO_MONEY;
        for (PreparedCandidate<K> candidate : prepared) {
            boolean awarded = awardedKeys.contains(candidate.stableKey());
            BigDecimal rounded = awarded
                    ? candidate.floorAmount().add(CENT)
                    : candidate.floorAmount();
            if (candidate.unroundedAmount().signum() != 0
                    && rounded.signum() != 0
                    && candidate.unroundedAmount().signum() != rounded.signum()) {
                throw failure(FailureCode.SIGN_REVERSAL);
            }
            allocations.add(new Allocation<>(
                    candidate.stableKey(),
                    candidate.unroundedAmount(),
                    candidate.floorAmount(),
                    candidate.fractionalCent(),
                    awarded,
                    rounded));
            roundedSum = roundedSum.add(rounded);
        }
        if (roundedSum.compareTo(centTarget) != 0) {
            throw failure(FailureCode.NON_CONSERVING_RESULT);
        }

        return new Result<>(allocations, centTarget, exactSum, remainingCentCount, targetMode);
    }

    public static <K extends Comparable<? super K>> Result<K> allocateOnceRounded(
            List<Candidate<K>> candidates) {
        return allocateOnceRounded(candidates, Comparator.naturalOrder());
    }

    public static <K> Result<K> allocateOnceRounded(
            List<Candidate<K>> candidates,
            Comparator<? super K> stableOrder) {
        if (candidates == null) {
            throw failure(FailureCode.NULL_INPUT);
        }
        BigDecimal exactSum = candidates.stream()
                .map(candidate -> {
                    if (candidate == null || candidate.unroundedAmount() == null) {
                        throw failure(FailureCode.NULL_AMOUNT);
                    }
                    return candidate.unroundedAmount();
                })
                .reduce(BigDecimal.ZERO, BigDecimal::add);
        BigDecimal target = exactSum.setScale(MONEY_SCALE, RoundingMode.HALF_UP);
        return allocate(target, candidates, stableOrder, TargetMode.ONCE_ROUNDED_PROVISIONAL);
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
            if (candidate.unroundedAmount() == null) {
                throw failure(FailureCode.NULL_AMOUNT);
            }
            if (!seenKeys.add(candidate.stableKey())) {
                throw failure(FailureCode.DUPLICATE_STABLE_KEY);
            }
            BigDecimal floor = candidate.unroundedAmount().setScale(MONEY_SCALE, RoundingMode.FLOOR);
            BigDecimal fractionalCent = candidate.unroundedAmount().movePointRight(MONEY_SCALE)
                    .subtract(floor.movePointRight(MONEY_SCALE));
            if (fractionalCent.compareTo(BigDecimal.ZERO) < 0
                    || fractionalCent.compareTo(BigDecimal.ONE) >= 0) {
                throw failure(FailureCode.INVALID_FRACTIONAL_CENT);
            }
            prepared.add(new PreparedCandidate<>(
                    candidate.stableKey(), candidate.unroundedAmount(), floor, fractionalCent));
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

    private static BigDecimal requireCentControl(BigDecimal targetControl) {
        try {
            return targetControl.setScale(MONEY_SCALE, RoundingMode.UNNECESSARY);
        } catch (ArithmeticException exception) {
            throw failure(FailureCode.TARGET_NOT_CENT_CONTROL);
        }
    }

    private static void validateTarget(
            BigDecimal targetControl,
            BigDecimal exactSum,
            TargetMode targetMode) {
        BigDecimal expected = targetMode == TargetMode.AUTHORITATIVE
                ? exactSum
                : exactSum.setScale(MONEY_SCALE, RoundingMode.HALF_UP);
        if (expected.compareTo(targetControl) != 0) {
            throw failure(FailureCode.TARGET_MISMATCH);
        }
    }

    private static int remainingCents(BigDecimal targetControl, BigDecimal floorSum) {
        try {
            return targetControl.subtract(floorSum)
                    .movePointRight(MONEY_SCALE)
                    .setScale(0, RoundingMode.UNNECESSARY)
                    .intValueExact();
        } catch (ArithmeticException exception) {
            throw failure(FailureCode.REMAINING_CENTS_NOT_INTEGER);
        }
    }

    private static AllocationException failure(FailureCode code) {
        return new AllocationException(code);
    }

    private record PreparedCandidate<K>(
            K stableKey,
            BigDecimal unroundedAmount,
            BigDecimal floorAmount,
            BigDecimal fractionalCent) {
    }
}
