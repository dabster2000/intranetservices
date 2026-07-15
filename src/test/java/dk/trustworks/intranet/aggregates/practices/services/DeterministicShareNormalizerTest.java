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

class DeterministicShareNormalizerTest {

    @Test
    void equalThirdsCloseExactlyOnLargestWeightThenStableKey() {
        DeterministicShareNormalizer.Result<String> result = normalize(
                share("C", "0.333333333333333333"),
                share("A", "0.333333333333333333"),
                share("B", "0.333333333333333333"));

        assertEquals(DeterministicShareNormalizer.Outcome.COMPLETE, result.outcome());
        assertEquals(new BigDecimal("0.999999999999999999"), result.rawShareSum());
        assertEquals(new BigDecimal("1.000000000000000000"), effectiveSum(result));
        assertEquals(List.of("A", "B", "C"),
                result.shares().stream().map(DeterministicShareNormalizer.NormalizedShare::stableKey).toList());
        assertEquals(new BigDecimal("0.333333333333333334"), result.shares().get(0).effectiveShare());
        assertTrue(result.shares().get(0).closureRow());
        assertFalse(result.shares().get(1).closureRow());
        assertTrue(result.normalizationApplied());
    }

    @Test
    void largestRawShareWinsClosureBeforeStableKeyTieBreak() {
        DeterministicShareNormalizer.Result<String> result = normalize(
                share("A", "0.200000000000000000"),
                share("B", "0.600000000000000000"),
                share("C", "0.200000000000000000"));

        assertEquals("B", result.shares().stream()
                .filter(DeterministicShareNormalizer.NormalizedShare::closureRow)
                .findFirst()
                .orElseThrow()
                .stableKey());
        assertEquals(new BigDecimal("1.000000000000000000"), effectiveSum(result));
        assertFalse(result.normalizationApplied());
    }

    @Test
    void manyRowRepeatingVectorClosesExactlyWithoutAnInputOrderDependency() {
        ArrayList<DeterministicShareNormalizer.Candidate<String>> rows = new ArrayList<>(
                IntStream.range(0, 7)
                        .mapToObj(index -> share("row-%02d".formatted(6 - index),
                                "0.142857142857142857"))
                        .toList());

        DeterministicShareNormalizer.Result<String> result =
                DeterministicShareNormalizer.normalize(
                        rows, false, "UNASSIGNED", Comparator.naturalOrder());

        assertEquals(new BigDecimal("1.000000000000000000"), effectiveSum(result));
        assertEquals("row-00", result.shares().stream()
                .filter(DeterministicShareNormalizer.NormalizedShare::closureRow)
                .findFirst()
                .orElseThrow()
                .stableKey());
        assertEquals(new BigDecimal("0.142857142857142858"), result.shares().get(0).effectiveShare());
        assertEquals(List.of("row-00", "row-01", "row-02", "row-03", "row-04", "row-05", "row-06"),
                result.shares().stream().map(DeterministicShareNormalizer.NormalizedShare::stableKey).toList());
    }

    @Test
    void vectorsAtEitherToleranceBoundaryCloseToExactlyOne() {
        DeterministicShareNormalizer.Result<String> lower = normalize(share("A", "0.9999"));
        DeterministicShareNormalizer.Result<String> upper = normalize(
                share("A", "0.50005"), share("B", "0.50005"));

        assertEquals(new BigDecimal("1.000000000000000000"), lower.shares().get(0).effectiveShare());
        assertEquals(new BigDecimal("1.000000000000000000"), effectiveSum(upper));
        assertTrue(lower.shares().get(0).closureRow());
        assertTrue(upper.shares().get(0).closureRow());
    }

    @Test
    void signedRatiosMayBeNegativeOrGreaterThanOneAndStillCloseExactly() {
        DeterministicShareNormalizer.SignedRatioResult<String> result =
                DeterministicShareNormalizer.normalizeSignedRatios(List.of(
                        new DeterministicShareNormalizer.SignedControlCandidate<>("item-b", new BigDecimal("-25.00")),
                        new DeterministicShareNormalizer.SignedControlCandidate<>("item-a", new BigDecimal("100.00"))));

        assertEquals(new BigDecimal("75.00"), result.signedControlSum());
        assertEquals(new BigDecimal("1.333333333333333333"), result.ratios().get(0).signedRatio());
        assertTrue(result.ratios().get(0).closureRow());
        assertEquals(new BigDecimal("-0.333333333333333333"), result.ratios().get(1).signedRatio());
        assertEquals(new BigDecimal("1.000000000000000000"), result.ratios().stream()
                .map(DeterministicShareNormalizer.NormalizedSignedRatio::signedRatio)
                .reduce(BigDecimal.ZERO.setScale(18), BigDecimal::add));
    }

    @Test
    void signedRatioClosureUsesLargestAbsoluteControlThenStableKey() {
        DeterministicShareNormalizer.SignedRatioResult<String> result =
                DeterministicShareNormalizer.normalizeSignedRatios(List.of(
                        new DeterministicShareNormalizer.SignedControlCandidate<>("B", new BigDecimal("-100")),
                        new DeterministicShareNormalizer.SignedControlCandidate<>("C", new BigDecimal("50")),
                        new DeterministicShareNormalizer.SignedControlCandidate<>("A", new BigDecimal("100"))));

        assertEquals("A", result.ratios().stream()
                .filter(DeterministicShareNormalizer.NormalizedSignedRatio::closureRow)
                .findFirst().orElseThrow().stableKey());
        assertEquals(new BigDecimal("2.000000000000000000"), result.ratios().get(0).signedRatio());
    }

    @Test
    void zeroOrNonRepresentableSignedRatioVectorFailsExplicitly() {
        assertFailure(DeterministicShareNormalizer.FailureCode.ZERO_SIGNED_CONTROL_SUM,
                () -> DeterministicShareNormalizer.normalizeSignedRatios(List.of(
                        new DeterministicShareNormalizer.SignedControlCandidate<>("A", BigDecimal.ONE),
                        new DeterministicShareNormalizer.SignedControlCandidate<>("B", BigDecimal.ONE.negate()))));
        assertFailure(DeterministicShareNormalizer.FailureCode.SIGNED_RATIO_OVERFLOW,
                () -> DeterministicShareNormalizer.normalizeSignedRatios(List.of(
                        new DeterministicShareNormalizer.SignedControlCandidate<>("A", BigDecimal.ONE),
                        new DeterministicShareNormalizer.SignedControlCandidate<>("B",
                                new BigDecimal("-0.999999999999999999999999999999")))));
    }

    @Test
    void scale18SourceNormalizationIsRecordedSeparatelyFromVectorNormalization() {
        DeterministicShareNormalizer.Result<String> result = normalize(
                share("A", "0.5000000000000000004"),
                share("B", "0.4999999999999999996"));

        assertEquals(new BigDecimal("0.500000000000000000"), result.shares().get(0).rawShare());
        assertTrue(result.shares().get(0).sourceNormalizationApplied());
        assertTrue(result.shares().get(1).sourceNormalizationApplied());
        assertFalse(result.normalizationApplied(),
                "the persisted raw vector already sums to one and needs no vector normalization");
    }

    @Test
    void permittedPartialVectorAddsOneExplicitUnassignedResidual() {
        DeterministicShareNormalizer.Result<String> result =
                DeterministicShareNormalizer.normalize(
                        List.of(share("consultant-a", "0.350000000000000000"),
                                share("consultant-b", "0.250000000000000000")),
                        true,
                        "UNASSIGNED",
                        Comparator.naturalOrder());

        assertEquals(DeterministicShareNormalizer.Outcome.PARTIAL_WITH_RESIDUAL, result.outcome());
        assertEquals(new BigDecimal("0.600000000000000000"), result.rawShareSum());
        assertEquals(3, result.shares().size());
        DeterministicShareNormalizer.NormalizedShare<String> residual = result.shares().get(2);
        assertEquals("UNASSIGNED", residual.stableKey());
        assertEquals(new BigDecimal("0.400000000000000000"), residual.rawShare());
        assertEquals(new BigDecimal("0.400000000000000000"), residual.effectiveShare());
        assertTrue(residual.residual());
        assertFalse(residual.closureRow());
        assertEquals(new BigDecimal("1.000000000000000000"), effectiveSum(result));
    }

    @Test
    void zeroSumIsTypedAsUnusableAndDoesNotInventARecipient() {
        DeterministicShareNormalizer.Result<String> result = normalize(
                share("A", "0"), share("B", "0.000000000000000000"));

        assertEquals(DeterministicShareNormalizer.Outcome.UNUSABLE_ZERO, result.outcome());
        assertEquals(new BigDecimal("0.000000000000000000"), result.rawShareSum());
        assertEquals(2, result.shares().size());
        assertTrue(result.shares().stream().allMatch(row ->
                row.effectiveShare().compareTo(BigDecimal.ZERO) == 0 && !row.closureRow() && !row.residual()));
    }

    @Test
    void invalidAndMateriallyIncompleteVectorsFailWithFiniteCodes() {
        assertFailure(DeterministicShareNormalizer.FailureCode.SHARE_OUT_OF_RANGE,
                () -> normalize(share("A", "-0.0000000000000000001")));
        assertFailure(DeterministicShareNormalizer.FailureCode.SHARE_OUT_OF_RANGE,
                () -> normalize(share("A", "1.0000000000000000001")));
        assertFailure(DeterministicShareNormalizer.FailureCode.SUM_EXCEEDS_TOLERANCE,
                () -> normalize(share("A", "0.6001"), share("B", "0.4001")));
        assertFailure(DeterministicShareNormalizer.FailureCode.PARTIAL_NOT_PERMITTED,
                () -> normalize(share("A", "0.999899999999999999")));
    }

    @Test
    void duplicateOrNonTotalStableKeysAreStructuralFailures() {
        assertFailure(DeterministicShareNormalizer.FailureCode.DUPLICATE_STABLE_KEY,
                () -> normalize(share("A", "0.5"), share("A", "0.5")));

        assertFailure(DeterministicShareNormalizer.FailureCode.NON_TOTAL_STABLE_ORDER,
                () -> DeterministicShareNormalizer.normalize(
                        List.of(share("A", "0.5"), share("B", "0.5")),
                        false,
                        "UNASSIGNED",
                        (left, right) -> 0));
    }

    @Test
    void resultsAndTheirShareListsAreImmutable() {
        ArrayList<DeterministicShareNormalizer.Candidate<String>> input = new ArrayList<>();
        input.add(share("A", "1"));
        DeterministicShareNormalizer.Result<String> result =
                DeterministicShareNormalizer.normalize(input, false, "UNASSIGNED", Comparator.naturalOrder());
        input.clear();

        assertEquals(1, result.shares().size());
        assertThrows(UnsupportedOperationException.class, () -> result.shares().clear());
    }

    @SafeVarargs
    private static DeterministicShareNormalizer.Result<String> normalize(
            DeterministicShareNormalizer.Candidate<String>... candidates) {
        return DeterministicShareNormalizer.normalize(
                List.of(candidates), false, "UNASSIGNED", Comparator.naturalOrder());
    }

    private static DeterministicShareNormalizer.Candidate<String> share(String key, String rawShare) {
        return new DeterministicShareNormalizer.Candidate<>(key, new BigDecimal(rawShare));
    }

    private static BigDecimal effectiveSum(DeterministicShareNormalizer.Result<String> result) {
        return result.shares().stream()
                .map(DeterministicShareNormalizer.NormalizedShare::effectiveShare)
                .reduce(BigDecimal.ZERO.setScale(DeterministicShareNormalizer.SHARE_SCALE), BigDecimal::add);
    }

    private static void assertFailure(
            DeterministicShareNormalizer.FailureCode expected,
            org.junit.jupiter.api.function.Executable executable) {
        DeterministicShareNormalizer.NormalizationException exception = assertThrows(
                DeterministicShareNormalizer.NormalizationException.class, executable);
        assertEquals(expected, exception.code());
        assertEquals(expected.name(), exception.getMessage());
    }
}
