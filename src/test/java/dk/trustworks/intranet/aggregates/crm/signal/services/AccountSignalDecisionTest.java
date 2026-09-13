package dk.trustworks.intranet.aggregates.crm.signal.services;

import dk.trustworks.intranet.aggregates.crm.signal.model.enums.SignalStatus;
import jakarta.ws.rs.WebApplicationException;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * Deciding a signal (CRM spec §3.4) — the half of {@link AccountSignalService} that the
 * capture-only release left unbuilt.
 *
 * <p>The ownership check itself needs a database and is exercised during verification.
 * What is locked here is the status vocabulary, which is deliberately stricter than the
 * TYPE vocabulary in {@link AccountSignalServiceTest}: an unrecognised signal TYPE must
 * never cost somebody their capture, so it silently becomes OTHER — but an unrecognised
 * DECISION is a caller bug, and guessing at one would record a verdict nobody gave.
 */
class AccountSignalDecisionTest {

    @Test
    void theThreeDecisionsParseCaseInsensitively() {
        assertEquals(SignalStatus.LEAD_CREATED, AccountSignalService.parseDecision("LEAD_CREATED"));
        assertEquals(SignalStatus.PARKED, AccountSignalService.parseDecision(" parked "));
        assertEquals(SignalStatus.NOT_RELEVANT, AccountSignalService.parseDecision("Not_Relevant"));
    }

    /**
     * NEW is a starting state, not a decision. Allowing it would let an API call quietly
     * rewind somebody's verdict, and an audit trail that can be rewound is not one.
     */
    @Test
    void aSignalCannotBeMovedBackToUndecided() {
        WebApplicationException error = assertThrows(WebApplicationException.class,
                () -> AccountSignalService.parseDecision("NEW"));
        assertEquals(400, error.getResponse().getStatus());
        assertEquals("A signal cannot be moved back to undecided", error.getMessage());
    }

    @Test
    void anUnknownOrAbsentDecisionIsRefusedRatherThanGuessed() {
        assertThrows(WebApplicationException.class, () -> AccountSignalService.parseDecision("MAYBE_LATER"));
        assertThrows(WebApplicationException.class, () -> AccountSignalService.parseDecision(null));
        assertThrows(WebApplicationException.class, () -> AccountSignalService.parseDecision("   "));
    }
}
