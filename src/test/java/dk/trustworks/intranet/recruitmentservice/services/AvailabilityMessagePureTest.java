package dk.trustworks.intranet.recruitmentservice.services;

import dk.trustworks.intranet.recruitmentservice.model.RecruitmentSchedulingRequest;
import org.junit.jupiter.api.Test;

import java.time.LocalDate;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The message loop's pure helpers (plan §12.2): participant check,
 * covered-range overlap for the supersede rule, and the spec §23
 * end-of-covered-period expiry stamp.
 */
class AvailabilityMessagePureTest {

    @Test
    void participates_coversRequiredAndOptionalInterviewers() {
        RecruitmentSchedulingRequest request = new RecruitmentSchedulingRequest();
        request.setInterviewerUuids(List.of("required-1"));
        request.setOptionalInterviewerUuids(List.of("optional-1"));
        assertTrue(AvailabilityMessageService.participates(request, "required-1"));
        assertTrue(AvailabilityMessageService.participates(request, "optional-1"));
        assertFalse(AvailabilityMessageService.participates(request, "outsider"));
        assertFalse(AvailabilityMessageService.participates(request, "recruiter-1"),
                "the recruiter talks through the panel, not the DM loop");
    }

    @Test
    void rangesDisjoint_nullRangesOverlapEverything() {
        LocalDate monday = LocalDate.of(2026, 8, 17);
        assertFalse(AvailabilityMessageService.rangesDisjoint(
                null, null, monday, monday), "unbounded evidence supersedes broadly");
        assertFalse(AvailabilityMessageService.rangesDisjoint(
                monday, monday.plusDays(2), monday.plusDays(2), monday.plusDays(4)),
                "touching ranges overlap");
        assertTrue(AvailabilityMessageService.rangesDisjoint(
                monday, monday.plusDays(1), monday.plusDays(2), monday.plusDays(3)));
    }

    @Test
    void expiry_isTheEndOfTheCoveredPeriod() {
        assertEquals(LocalDate.of(2026, 8, 21).atTime(23, 59, 59),
                AvailabilityMessageService.expiryOf(LocalDate.of(2026, 8, 21)));
        assertNull(AvailabilityMessageService.expiryOf(null),
                "no covered range, no timed expiry — the 48 h pending rule still applies");
    }

    @org.junit.jupiter.api.Test
    void onlyFullPicturesAndCorrections_replaceOlderEvidence() {
        // Backlog fix 2026-08-15: additive statements (a busy hour, an
        // available interval, a preference) LAYER on top of what was
        // said before — the retest showed a one-hour busy note retiring
        // a whole week's availability statement via the blind
        // range-overlap supersede.
        org.junit.jupiter.api.Assertions.assertTrue(
                AvailabilityMessageService.replacesOlderEvidence("PROVIDE_AVAILABILITY"));
        org.junit.jupiter.api.Assertions.assertTrue(
                AvailabilityMessageService.replacesOlderEvidence(
                        "CORRECT_PRIOR_INTERPRETATION"));
        org.junit.jupiter.api.Assertions.assertFalse(
                AvailabilityMessageService.replacesOlderEvidence("ADD_BUSY_INTERVAL"));
        org.junit.jupiter.api.Assertions.assertFalse(
                AvailabilityMessageService.replacesOlderEvidence("ADD_AVAILABLE_INTERVAL"));
        org.junit.jupiter.api.Assertions.assertFalse(
                AvailabilityMessageService.replacesOlderEvidence("ADD_PREFERENCE"));
        org.junit.jupiter.api.Assertions.assertFalse(
                AvailabilityMessageService.replacesOlderEvidence("UNKNOWN"));
    }
}
