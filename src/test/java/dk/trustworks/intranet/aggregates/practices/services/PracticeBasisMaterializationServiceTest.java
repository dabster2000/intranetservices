package dk.trustworks.intranet.aggregates.practices.services;

import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.math.BigInteger;
import java.time.LocalDate;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;

class PracticeBasisMaterializationServiceTest {
    @Test
    void transferSplitsDailyCapacityAndPreHistoryUsesDisclosedFallback() {
        var service = new PracticeBasisMaterializationService();
        service.resolver = new EffectivePracticeDateResolver();
        var provider = new PracticeRevenueDependencyManifestProvider();
        var manifest = provider.fromDependencies(List.of(), date("2026-07-01"), date("2026-09-30"));
        var input = new PracticeBasisMaterializationService.BuildInput("generation", manifest,
                BigInteger.ONE, BigInteger.ZERO, BigInteger.ONE, List.of(
                new PracticeBasisMaterializationService.UserBasisInput("user", "CONSULTANT", "PM",
                        List.of(
                                history("2026-07-14", "2026-09-01", "BA"),
                                history("2026-09-01", null, "DEV")),
                        List.of(capacity("2026-07-13"), capacity("2026-07-14"), capacity("2026-09-01")))));

        var prepared = service.prepare(input);
        assertEquals(List.of("PM", "BA", "DEV"), prepared.capacityRows().stream()
                .map(PracticeBasisMaterializationService.CapacityRow::practiceCode).toList());
        assertEquals(List.of(true, false, false), prepared.capacityRows().stream()
                .map(PracticeBasisMaterializationService.CapacityRow::historicalFallback).toList());
        assertEquals(64, prepared.sourceFingerprint().length());
    }

    /**
     * A first history interval that begins after coverage (e.g. the production history-seeding date)
     * must not leak into the fallback segment's end: unclamped it reaches revenue dependency envelopes
     * as an uncoverable capacity end, and the resulting BASIS_COVERAGE_MISS escalation loops forever
     * because the dependency manifest never enumerates capacity horizons (observed on staging, where
     * every DEPENDENCY_MANIFEST_INPUT escalation rebuilt a byte-identical basis).
     */
    @Test
    void preHistoryFallbackEndIsClampedToCoverageWhenHistoryStartsAfterCoverage() {
        var service = new PracticeBasisMaterializationService();
        service.resolver = new EffectivePracticeDateResolver();
        var provider = new PracticeRevenueDependencyManifestProvider();
        var manifest = provider.fromDependencies(List.of(), date("2020-09-01"), date("2026-07-01"));
        var input = new PracticeBasisMaterializationService.BuildInput("generation", manifest,
                BigInteger.ONE, BigInteger.ZERO, BigInteger.ONE, List.of(
                new PracticeBasisMaterializationService.UserBasisInput("user", "CONSULTANT", "PM",
                        List.of(history("2026-07-14", null, "PM")),
                        List.of(capacity("2026-06-30")))));

        var prepared = service.prepare(input);
        assertEquals(1, prepared.effectiveRows().size());
        var row = prepared.effectiveRows().getFirst();
        assertEquals(date("2026-07-02"), row.effectiveToExclusive());
        assertEquals("CURRENT_PRACTICE_FALLBACK", row.attributionBasis());
        assertEquals(List.of(true), prepared.capacityRows().stream()
                .map(PracticeBasisMaterializationService.CapacityRow::historicalFallback).toList());
    }

    private static EffectivePracticeDateResolver.HistoryInterval history(String from, String to, String practice) {
        return new EffectivePracticeDateResolver.HistoryInterval(date(from), to == null ? null : date(to),
                practice, "TEST");
    }
    private static PracticeBasisMaterializationService.CapacityInput capacity(String date) {
        return new PracticeBasisMaterializationService.CapacityInput(date(date), "company",
                new BigDecimal("7.400000"), "SCHEDULE", "fingerprint");
    }
    private static LocalDate date(String value) { return LocalDate.parse(value); }
}
