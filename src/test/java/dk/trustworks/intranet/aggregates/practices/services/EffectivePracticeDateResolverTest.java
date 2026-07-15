package dk.trustworks.intranet.aggregates.practices.services;

import org.junit.jupiter.api.Test;

import java.time.LocalDate;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class EffectivePracticeDateResolverTest {
    private final EffectivePracticeDateResolver resolver = new EffectivePracticeDateResolver();

    @Test
    void resolvesHalfOpenTransferBoundaryAndPreHistoryFallback() {
        List<EffectivePracticeDateResolver.HistoryInterval> history = List.of(
                row("2026-07-14", "2026-09-01", "BA"),
                row("2026-09-01", null, "DEV"));

        assertEquals(EffectivePracticeDateResolver.Status.CURRENT_PRACTICE_FALLBACK,
                resolver.resolve(date("2026-07-13"), history, "PM").status());
        assertEquals("BA", resolver.resolve(date("2026-08-31"), history, "PM").practice());
        assertEquals("DEV", resolver.resolve(date("2026-09-01"), history, "PM").practice());
    }

    @Test
    void refusesOverlapAndCoveredHistoryGap() {
        var exception = assertThrows(EffectivePracticeDateResolver.InvalidPracticeHistoryException.class,
                () -> resolver.resolve(date("2026-08-01"), List.of(
                row("2026-07-01", "2026-09-01", "BA"),
                row("2026-08-01", null, "DEV")), "PM"));
        assertEquals("OVERLAPPING_HISTORY", exception.getMessage());

        var resolution = resolver.resolve(date("2026-08-15"), List.of(
                row("2026-07-01", "2026-08-01", "BA"),
                row("2026-09-01", null, "DEV")), "PM");
        assertEquals(EffectivePracticeDateResolver.Status.UNAVAILABLE, resolution.status());
        assertEquals(EffectivePracticeDateResolver.Reason.HISTORY_GAP, resolution.reason());
    }

    private static EffectivePracticeDateResolver.HistoryInterval row(String from, String to, String practice) {
        return new EffectivePracticeDateResolver.HistoryInterval(
                date(from), to == null ? null : date(to), practice, "TEST");
    }

    private static LocalDate date(String value) { return LocalDate.parse(value); }
}
