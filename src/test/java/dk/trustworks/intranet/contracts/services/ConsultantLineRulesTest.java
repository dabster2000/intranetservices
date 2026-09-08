package dk.trustworks.intranet.contracts.services;

import org.junit.jupiter.api.Test;

import java.time.LocalDate;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** WP5 pricing-model reference rule and WP4b zero-rate declaration rule (spec §4.4.1, §4.4.6). */
class ConsultantLineRulesTest {

    private static final Set<String> ACTIVE = Set.of("FULL_FROM_START", "STEPPED", "PILOT_FREE", "COLLEAGUE_HOURS");
    private static final LocalDate REVIEW = LocalDate.of(2026, 10, 31);

    @Test
    void pricingModelIsOptionalButMustBeActiveWhenGiven() {
        assertNull(ConsultantLineRules.pricingModelProblem(null, ACTIVE));
        assertNull(ConsultantLineRules.pricingModelProblem("", ACTIVE));
        assertNull(ConsultantLineRules.pricingModelProblem("STEPPED", ACTIVE));
        assertEquals("Unknown or inactive pricing model 'RETIRED'",
                ConsultantLineRules.pricingModelProblem("RETIRED", ACTIVE));
    }

    @Test
    void hourlyAssignmentRequiresAnExplicitActiveModel() {
        assertEquals("Pricing model is required for consultants paid by the hour during the assignment",
                ConsultantLineRules.pricingModelProblem(null, ACTIVE, true));
        assertEquals("Pricing model is required for consultants paid by the hour during the assignment",
                ConsultantLineRules.pricingModelProblem("  ", ACTIVE, true));
        assertNull(ConsultantLineRules.pricingModelProblem("COLLEAGUE_HOURS", ACTIVE, true));
        assertEquals("Unknown or inactive pricing model 'RETIRED'",
                ConsultantLineRules.pricingModelProblem("RETIRED", ACTIVE, true));
    }

    @Test
    void fullyDeclaredZeroIsAccepted() {
        assertTrue(ConsultantLineRules.zeroRateProblems(0.0, "PILOT_FREE", REVIEW, 600.0).isEmpty());
        assertTrue(ConsultantLineRules.zeroRateProblems(0.0, "GOODWILL", REVIEW, 450.0).isEmpty());
    }

    @Test
    void bareZeroIsRefusedNamingAllThreeFields() {
        List<String> problems = ConsultantLineRules.zeroRateProblems(0.0, null, null, null);
        assertEquals(List.of("A rate of 0 must be declared with a zero-rate reason, a rate review date, a list rate"), problems);
    }

    @Test
    void zeroMissingOnlyTheListRateNamesJustThat() {
        List<String> problems = ConsultantLineRules.zeroRateProblems(0.0, "PILOT_FREE", REVIEW, null);
        assertEquals(List.of("A rate of 0 must be declared with a list rate"), problems);
    }

    @Test
    void zeroWithZeroListRateIsRefused() {
        List<String> problems = ConsultantLineRules.zeroRateProblems(0.0, "PILOT_FREE", REVIEW, 0.0);
        assertEquals(List.of("The list rate must be greater than 0"), problems);
    }

    @Test
    void unknownReasonIsRefused() {
        List<String> problems = ConsultantLineRules.zeroRateProblems(0.0, "FREEBIE", REVIEW, 600.0);
        assertEquals(1, problems.size());
        assertTrue(problems.get(0).startsWith("Unknown zero-rate reason 'FREEBIE'"));
    }

    @Test
    void positiveRateNeedsNoDeclarationAndRefusesAStrayReason() {
        assertTrue(ConsultantLineRules.zeroRateProblems(600.0, null, null, null).isEmpty());
        assertTrue(ConsultantLineRules.zeroRateProblems(600.0, null, REVIEW, 600.0).isEmpty());
        assertEquals(List.of("A zero-rate reason only applies to a rate of 0"),
                ConsultantLineRules.zeroRateProblems(600.0, "PILOT_FREE", REVIEW, 600.0));
    }

    @Test
    void negativeRateIsRefused() {
        assertEquals(List.of("Rate must not be negative"), ConsultantLineRules.zeroRateProblems(-1.0, null, null, null));
    }
}
