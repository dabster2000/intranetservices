package dk.trustworks.intranet.aggregates.invoice.selfbilled.services;

import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class AssignmentSuggesterTest {

    @Test
    void mapped_code_with_period_is_high_confidence_and_boosted_by_work_match() {
        var in = new AssignmentSuggester.SuggesterInput("MC", 2025, 8, "michelle-uuid",
                new BigDecimal("153525.00"),
                Map.of("michelle-uuid", new BigDecimal("153525.00")),   // doc-exact (spec §1.3 common case)
                Map.of());
        List<AssignmentSuggester.Suggestion> out = AssignmentSuggester.suggest(in);
        assertEquals(1, out.size());
        var s = out.get(0);
        assertEquals("michelle-uuid", s.consultantUuid());
        assertEquals(2025, s.workYear());
        assertEquals(8, s.workMonth());
        assertEquals(95, s.confidence());
        assertTrue(s.reason().contains("code map"));
    }

    @Test
    void mapped_code_without_work_match_is_90() {
        var in = new AssignmentSuggester.SuggesterInput("MC", 2025, 8, "michelle-uuid",
                new BigDecimal("153525.00"),
                Map.of("michelle-uuid", new BigDecimal("162725.00")),   // 8h gap — still code-mapped
                Map.of());
        var s = AssignmentSuggester.suggest(in).get(0);
        assertEquals(90, s.confidence());
        // The code-map floor must clear the same-company bulk-accept gate (AC2).
        assertTrue(s.confidence() >= AssignmentSuggester.HIGH_CONFIDENCE);
    }

    @Test
    void mapped_code_with_no_registered_work_is_90() {
        // Code map hit, but no registered work for that consultant at all — no NPE, still 90.
        var in = new AssignmentSuggester.SuggesterInput("MC", 2025, 8, "michelle-uuid",
                new BigDecimal("153525.00"),
                Map.of(),
                Map.of());
        var s = AssignmentSuggester.suggest(in).get(0);
        assertEquals("michelle-uuid", s.consultantUuid());
        assertEquals(90, s.confidence());
    }

    @Test
    void unmapped_code_falls_back_to_prior_assignment() {
        var in = new AssignmentSuggester.SuggesterInput("MHB", 2025, 8, null,
                new BigDecimal("100000.00"), Map.of(), Map.of("MHB", "mads-uuid"));
        var s = AssignmentSuggester.suggest(in).get(0);
        assertEquals("mads-uuid", s.consultantUuid());
        assertEquals(70, s.confidence());
        assertTrue(s.reason().contains("prior assignment"));
    }

    @Test
    void no_code_but_unique_amount_match_is_medium() {
        var in = new AssignmentSuggester.SuggesterInput(null, 2025, 8, null,
                new BigDecimal("81030.00"),
                Map.of("sb-uuid", new BigDecimal("81030.50"), "other", new BigDecimal("5000")),
                Map.of());
        var s = AssignmentSuggester.suggest(in).get(0);
        assertEquals("sb-uuid", s.consultantUuid());
        assertEquals(50, s.confidence());
    }

    @Test
    void ambiguous_amount_match_yields_nothing() {
        var in = new AssignmentSuggester.SuggesterInput(null, 2025, 8, null,
                new BigDecimal("81030.00"),
                Map.of("a", new BigDecimal("81030.00"), "b", new BigDecimal("81030.40")),
                Map.of());
        assertTrue(AssignmentSuggester.suggest(in).isEmpty());
    }

    @Test
    void tolerance_boundary_one_krone_inclusive() {
        // Signal-3 path: a gap of exactly 1.00 kr matches (tolerance is inclusive)...
        var atBoundary = new AssignmentSuggester.SuggesterInput(null, 2025, 8, null,
                new BigDecimal("81030.00"),
                Map.of("sb-uuid", new BigDecimal("81031.00")),
                Map.of());
        var s = AssignmentSuggester.suggest(atBoundary).get(0);
        assertEquals("sb-uuid", s.consultantUuid());
        assertEquals(50, s.confidence());

        // ...while a gap of 1.01 kr does not.
        var beyondBoundary = new AssignmentSuggester.SuggesterInput(null, 2025, 8, null,
                new BigDecimal("81030.00"),
                Map.of("sb-uuid", new BigDecimal("81031.01")),
                Map.of());
        assertTrue(AssignmentSuggester.suggest(beyondBoundary).isEmpty());
    }

    @Test
    void no_period_yields_nothing() {
        var in = new AssignmentSuggester.SuggesterInput("MC", null, null, "michelle-uuid",
                new BigDecimal("100"), Map.of(), Map.of());
        assertTrue(AssignmentSuggester.suggest(in).isEmpty());
    }
}
