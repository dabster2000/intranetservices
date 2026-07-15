package dk.trustworks.intranet.aggregates.practices.services;

import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.stream.IntStream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class BalancedCentAllocatorTest {

    @Test
    void oneAuthoritativeRowKeepsItsExactCentControl() {
        BalancedCentAllocator.Result<String> result = authoritative(
                "123.45", amount("item", "123.450000000000000000"));

        assertEquals(new BigDecimal("123.45"), result.targetControl());
        assertEquals(0, result.remainingCentCount());
        assertEquals(new BigDecimal("123.45"), result.allocations().get(0).roundedAmount());
        assertFalse(result.allocations().get(0).centAwarded());
    }

    @Test
    void equalResiduesUseStableKeyAndAwardAtMostOneCent() {
        BalancedCentAllocator.Result<String> result = authoritative(
                "1.00",
                amount("B", "0.005"),
                amount("A", "0.005"),
                amount("C", "0.990"));

        assertEquals(List.of("A", "B", "C"),
                result.allocations().stream().map(BalancedCentAllocator.Allocation::stableKey).toList());
        assertEquals(new BigDecimal("0.01"), result.allocations().get(0).roundedAmount());
        assertTrue(result.allocations().get(0).centAwarded());
        assertEquals(new BigDecimal("0.00"), result.allocations().get(1).roundedAmount());
        assertFalse(result.allocations().get(1).centAwarded());
        assertEquals(new BigDecimal("0.5"), result.allocations().get(0).fractionalCent());
        assertConserves(result);
    }

    @Test
    void mixedSignsUseMathematicalFloorWithoutReversingEconomicSign() {
        BalancedCentAllocator.Result<String> result = authoritative(
                "1.00",
                amount("positive", "1.005"),
                amount("negative", "-0.005"));

        assertEquals(new BigDecimal("0.00"), allocation(result, "negative").roundedAmount());
        assertEquals(new BigDecimal("1.00"), allocation(result, "positive").roundedAmount());
        assertTrue(result.allocations().stream().allMatch(row ->
                row.roundedAmount().signum() == 0
                        || row.unroundedAmount().signum() == 0
                        || row.roundedAmount().signum() == row.unroundedAmount().signum()));
        assertConserves(result);
    }

    @Test
    void positiveSixtyCentsAcrossOneHundredRowsAwardsExactlySixtyRows() {
        List<BalancedCentAllocator.Candidate<String>> rows = hundredRows("0.006");

        BalancedCentAllocator.Result<String> result = BalancedCentAllocator.allocate(
                new BigDecimal("0.60"), rows, Comparator.naturalOrder(),
                BalancedCentAllocator.TargetMode.AUTHORITATIVE);

        assertEquals(60, result.remainingCentCount());
        assertEquals(60, result.allocations().stream().filter(BalancedCentAllocator.Allocation::centAwarded).count());
        assertEquals(60, result.allocations().stream()
                .filter(row -> row.roundedAmount().compareTo(new BigDecimal("0.01")) == 0).count());
        assertEquals(40, result.allocations().stream()
                .filter(row -> row.roundedAmount().compareTo(new BigDecimal("0.00")) == 0).count());
        assertConserves(result);
    }

    @Test
    void negativeSixtyCentsAcrossOneHundredRowsAwardsFortyRowsBackTowardZero() {
        List<BalancedCentAllocator.Candidate<String>> rows = hundredRows("-0.006");

        BalancedCentAllocator.Result<String> result = BalancedCentAllocator.allocate(
                new BigDecimal("-0.60"), rows, Comparator.naturalOrder(),
                BalancedCentAllocator.TargetMode.AUTHORITATIVE);

        assertEquals(40, result.remainingCentCount());
        assertEquals(40, result.allocations().stream().filter(BalancedCentAllocator.Allocation::centAwarded).count());
        assertEquals(40, result.allocations().stream()
                .filter(row -> row.roundedAmount().compareTo(new BigDecimal("0.00")) == 0).count());
        assertEquals(60, result.allocations().stream()
                .filter(row -> row.roundedAmount().compareTo(new BigDecimal("-0.01")) == 0).count());
        assertConserves(result);
    }

    @Test
    void provisionalTargetIsRoundedOnceAndMayAwardEveryRow() {
        List<BalancedCentAllocator.Candidate<String>> rows = List.of(
                amount("A", "0.009"), amount("B", "0.009"));

        BalancedCentAllocator.Result<String> result = BalancedCentAllocator.allocateOnceRounded(
                rows, Comparator.naturalOrder());

        assertEquals(new BigDecimal("0.02"), result.targetControl());
        assertEquals(2, result.remainingCentCount());
        assertTrue(result.allocations().stream().allMatch(BalancedCentAllocator.Allocation::centAwarded));
        assertConserves(result);
    }

    @Test
    void closedShareVectorMultipliesAndAllocatesWithoutASeparateRemainderSink() {
        DeterministicShareNormalizer.Result<String> shares = DeterministicShareNormalizer.normalize(
                List.of(
                        new DeterministicShareNormalizer.Candidate<>("C", new BigDecimal("0.333333333333333333")),
                        new DeterministicShareNormalizer.Candidate<>("A", new BigDecimal("0.333333333333333333")),
                        new DeterministicShareNormalizer.Candidate<>("B", new BigDecimal("0.333333333333333333"))),
                false,
                "UNASSIGNED",
                Comparator.naturalOrder());
        List<BalancedCentAllocator.Candidate<String>> rows = shares.shares().stream()
                .map(share -> new BalancedCentAllocator.Candidate<>(
                        share.stableKey(), new BigDecimal("0.01").multiply(share.effectiveShare())))
                .toList();

        BalancedCentAllocator.Result<String> result = BalancedCentAllocator.allocate(
                new BigDecimal("0.01"), rows, Comparator.naturalOrder(),
                BalancedCentAllocator.TargetMode.AUTHORITATIVE);

        assertEquals(new BigDecimal("0.01"), allocation(result, "A").roundedAmount());
        assertEquals(new BigDecimal("0.00"), allocation(result, "B").roundedAmount());
        assertEquals(new BigDecimal("0.00"), allocation(result, "C").roundedAmount());
        assertConserves(result);
    }

    @Test
    void explicitProvisionalTargetMustEqualTheOnceRoundedExactSum() {
        assertFailure(BalancedCentAllocator.FailureCode.TARGET_MISMATCH,
                () -> BalancedCentAllocator.allocate(
                        new BigDecimal("0.01"),
                        List.of(amount("A", "0.009"), amount("B", "0.009")),
                        Comparator.naturalOrder(),
                        BalancedCentAllocator.TargetMode.ONCE_ROUNDED_PROVISIONAL));
    }

    @Test
    void targetAndAuthoritativeVectorMustHaveExactRequiredPrecisionAndSum() {
        assertFailure(BalancedCentAllocator.FailureCode.TARGET_NOT_CENT_CONTROL,
                () -> authoritative("1.001", amount("A", "1.001")));
        assertFailure(BalancedCentAllocator.FailureCode.TARGET_MISMATCH,
                () -> authoritative("1.00", amount("A", "0.999")));
    }

    @Test
    void duplicateOrNonTotalStableKeysFailExplicitly() {
        assertFailure(BalancedCentAllocator.FailureCode.DUPLICATE_STABLE_KEY,
                () -> authoritative("1.00", amount("A", "0.5"), amount("A", "0.5")));
        assertFailure(BalancedCentAllocator.FailureCode.NON_TOTAL_STABLE_ORDER,
                () -> BalancedCentAllocator.allocate(
                        new BigDecimal("1.00"),
                        List.of(amount("A", "0.5"), amount("B", "0.5")),
                        (left, right) -> 0,
                        BalancedCentAllocator.TargetMode.AUTHORITATIVE));
    }

    @Test
    void outputOrderingAndValuesDoNotDependOnInputOrdering() {
        List<BalancedCentAllocator.Candidate<String>> first = List.of(
                amount("C", "0.334"), amount("A", "0.333"), amount("B", "0.333"));
        ArrayList<BalancedCentAllocator.Candidate<String>> reversed = new ArrayList<>(first);
        java.util.Collections.reverse(reversed);

        BalancedCentAllocator.Result<String> left = BalancedCentAllocator.allocate(
                new BigDecimal("1.00"), first, Comparator.naturalOrder(),
                BalancedCentAllocator.TargetMode.AUTHORITATIVE);
        BalancedCentAllocator.Result<String> right = BalancedCentAllocator.allocate(
                new BigDecimal("1.00"), reversed, Comparator.naturalOrder(),
                BalancedCentAllocator.TargetMode.AUTHORITATIVE);

        assertEquals(left, right);
        assertThrows(UnsupportedOperationException.class, () -> left.allocations().clear());
    }

    @SafeVarargs
    private static BalancedCentAllocator.Result<String> authoritative(
            String target,
            BalancedCentAllocator.Candidate<String>... candidates) {
        return BalancedCentAllocator.allocate(
                new BigDecimal(target), List.of(candidates), Comparator.naturalOrder(),
                BalancedCentAllocator.TargetMode.AUTHORITATIVE);
    }

    private static BalancedCentAllocator.Candidate<String> amount(String key, String value) {
        return new BalancedCentAllocator.Candidate<>(key, new BigDecimal(value));
    }

    private static List<BalancedCentAllocator.Candidate<String>> hundredRows(String value) {
        return IntStream.range(0, 100)
                .mapToObj(index -> amount("row-%03d".formatted(index), value))
                .toList();
    }

    private static BalancedCentAllocator.Allocation<String> allocation(
            BalancedCentAllocator.Result<String> result,
            String key) {
        return result.allocations().stream()
                .filter(row -> row.stableKey().equals(key))
                .findFirst()
                .orElseThrow();
    }

    private static void assertConserves(BalancedCentAllocator.Result<String> result) {
        BigDecimal sum = result.allocations().stream()
                .map(BalancedCentAllocator.Allocation::roundedAmount)
                .reduce(BigDecimal.ZERO.setScale(2), BigDecimal::add);
        assertEquals(result.targetControl(), sum);
    }

    private static void assertFailure(
            BalancedCentAllocator.FailureCode expected,
            org.junit.jupiter.api.function.Executable executable) {
        BalancedCentAllocator.AllocationException exception = assertThrows(
                BalancedCentAllocator.AllocationException.class, executable);
        assertEquals(expected, exception.code());
        assertEquals(expected.name(), exception.getMessage());
    }
}
