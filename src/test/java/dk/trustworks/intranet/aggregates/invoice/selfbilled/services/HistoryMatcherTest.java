package dk.trustworks.intranet.aggregates.invoice.selfbilled.services;

import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/** AC5: exact matches auto-clear; only mismatches (incl. internals with NO backing assignment) surface. */
class HistoryMatcherTest {

    private static HistoryReconciliationService.BookedGroup booked(String c, int y, int m, String amount) {
        return new HistoryReconciliationService.BookedGroup(c, y, m, new BigDecimal(amount), List.of("inv-" + c));
    }

    @Test
    void exact_match_auto_clears() {
        var rows = HistoryReconciliationService.matchRows(
                List.of(booked("michelle", 2025, 8, "153525.00")),
                Map.of("michelle|2025|8", new BigDecimal("153525.00")));
        assertTrue(rows.isEmpty(), "booked == assigned must auto-clear (Michelle case)");
    }

    @Test
    void within_one_krone_auto_clears() {
        var rows = HistoryReconciliationService.matchRows(
                List.of(booked("a", 2025, 8, "1000.00")),
                Map.of("a|2025|8", new BigDecimal("1000.60")));
        assertTrue(rows.isEmpty());
    }

    @Test
    void mismatch_surfaces_with_proposed_delta() {
        var rows = HistoryReconciliationService.matchRows(
                List.of(booked("a", 2025, 8, "100000.00")),
                Map.of("a|2025|8", new BigDecimal("90000.00")));
        assertEquals(1, rows.size());
        assertEquals(-10000.00, rows.get(0).proposedDelta(), 0.001);   // over-booked -> credit note
    }

    @Test
    void internal_without_backing_assignment_surfaces() {
        var rows = HistoryReconciliationService.matchRows(
                List.of(booked("a", 2025, 8, "5000.00")), Map.of());
        assertEquals(1, rows.size());
        assertEquals(0.0, rows.get(0).assigned(), 0.001);
    }
}
