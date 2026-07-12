package dk.trustworks.intranet.aggregates.executive.services;

import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ExecutivePeopleCareerServiceTest {

    @Test
    void priorYearCareerMixRequiresMaterialAssignedCoverage() {
        assertFalse(ExecutivePeopleCareerService.hasComparablePriorCareerMix(Map.of(
                "Unassigned", 114L), java.util.Set.of()));
        assertFalse(ExecutivePeopleCareerService.hasComparablePriorCareerMix(Map.of(
                "Professional", 79L,
                "Unassigned", 21L), java.util.Set.of()));
        assertTrue(ExecutivePeopleCareerService.hasComparablePriorCareerMix(Map.of(
                "Professional", 80L,
                "Unassigned", 20L), java.util.Set.of()));
    }

    @Test
    void priorYearCareerMixRequiresAtLeastEightyPercentPrivacyVisible() {
        Map<String, Long> counts = Map.of(
                "Professional", 80L,
                "Senior / Advisory", 18L,
                "Executive", 2L);
        assertFalse(ExecutivePeopleCareerService.hasComparablePriorCareerMix(
                counts, java.util.Set.of("Professional", "Executive")));
        assertTrue(ExecutivePeopleCareerService.hasComparablePriorCareerMix(
                counts, java.util.Set.of("Senior / Advisory", "Executive")));
        // Privacy floor disabled: a small but fully-assigned, fully-visible prior year now qualifies
        // (only a truly-empty prior year is excluded).
        assertTrue(ExecutivePeopleCareerService.hasComparablePriorCareerMix(
                Map.of("Professional", 2L), java.util.Set.of()));
        assertFalse(ExecutivePeopleCareerService.hasComparablePriorCareerMix(
                Map.of(), java.util.Set.of()));
    }
}
