package dk.trustworks.intranet.recruitmentservice.services;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The verdict now carries the four per-check booleans, so the INFO line can
 * say WHICH check failed. Without them, the eight days in August 2026 when
 * gpt-4o-mini refused all 20 uploads left no evidence at all of the reason —
 * the model's text goes only to the candidate, in the 422 body.
 */
class OnboardingDecisionChecksTest {

    @Test
    @DisplayName("An approval carries all four checks as true")
    void parseDecision_approvedCarriesChecks() {
        var d = OnboardingDocumentValidationService.parseDecision("""
                {
                  "approved": true,
                  "reason": "Valid Danish driver's licence, clearly legible.",
                  "checks": {
                    "isCorrectDocumentType": true,
                    "isDanish": true,
                    "isReadable": true,
                    "isValid": true
                  }
                }
                """);
        assertTrue(d.approved());
        assertNotNull(d.checks());
        assertTrue(d.checks().correctDocumentType());
        assertTrue(d.checks().danish());
        assertTrue(d.checks().readable());
        assertTrue(d.checks().valid());
    }

    @Test
    @DisplayName("A rejection names the failing check — the gpt-4o-mini signature")
    void parseDecision_rejectionIdentifiesTheFailingCheck() {
        // Exactly the answer shape that produced the outage: the document is
        // the right type and Danish and in date; the model just would not
        // commit to having read it.
        var d = OnboardingDocumentValidationService.parseDecision("""
                {
                  "approved": false,
                  "reason": "The text is not clearly legible.",
                  "checks": {
                    "isCorrectDocumentType": true,
                    "isDanish": true,
                    "isReadable": false,
                    "isValid": true
                  }
                }
                """);
        assertFalse(d.approved());
        assertNotNull(d.checks());
        assertFalse(d.checks().readable(), "readable=false is the diagnosable signal");
        assertTrue(d.checks().correctDocumentType());
    }

    @Test
    @DisplayName("An approved/checks mismatch still carries the checks it disagreed with")
    void parseDecision_mismatchKeepsChecks() {
        var d = OnboardingDocumentValidationService.parseDecision("""
                {
                  "approved": true,
                  "reason": "Looks fine to me.",
                  "checks": {
                    "isCorrectDocumentType": true,
                    "isDanish": false,
                    "isReadable": true,
                    "isValid": true
                  }
                }
                """);
        assertFalse(d.approved(), "the per-check booleans win over the top-level claim");
        assertNotNull(d.checks());
        assertFalse(d.checks().danish());
    }

    @Test
    @DisplayName("Paths that never reached a model verdict carry no checks")
    void parseDecision_noVerdictLeavesChecksNull() {
        assertNull(OnboardingDocumentValidationService.parseDecision("{}").checks());
        assertNull(OnboardingDocumentValidationService.parseDecision("not json").checks());
    }

    @Test
    @DisplayName("The override threshold is two rejections, not one")
    void overrideThresholdIsTwo() {
        // One refusal is usually the honest answer to a bad photo; the
        // retake loop should happen. Two is where insisting stops helping.
        assertEquals(2, OnboardingAttemptRecorder.REJECTIONS_BEFORE_OVERRIDE);
    }
}
