package dk.trustworks.intranet.aggregates.bonus.individual.services;

import org.junit.jupiter.api.Test;

import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class IndividualBonusDuplicateClassifierTest {

    @Test
    void acceptsOnlyAnExplicitlyNamedDuplicateConstraint() {
        Set<String> accepted = Set.of("uk_individual_bonus_payout_source_ref");
        assertTrue(IndividualBonusDuplicateClassifier.isNamedUniqueViolation(
                new RuntimeException("Duplicate entry for key 'uk_individual_bonus_payout_source_ref'"), accepted));
        assertFalse(IndividualBonusDuplicateClassifier.isNamedUniqueViolation(
                new RuntimeException("Duplicate entry for key 'unrelated_unique_key'"), accepted));
        assertFalse(IndividualBonusDuplicateClassifier.isNamedUniqueViolation(
                new RuntimeException("connection reset"), accepted));
    }
}
