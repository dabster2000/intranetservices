package dk.trustworks.intranet.communicationsservice.resources;

import dk.trustworks.intranet.communicationsservice.model.enums.MailStatus;
import dk.trustworks.intranet.communicationsservice.resources.MailResource.ClaimOutcome;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The V494 drain rules — the pure decisions behind the batch outbox
 * drain. The load-bearing invariants:
 * <ul>
 *   <li><b>A poison mail parks, it never stalls.</b> Pre-V494, one
 *       permanently failing mail held the whole queue forever (the send
 *       threw, the transaction rolled back, the row stayed READY and was
 *       picked first again every run).</li>
 *   <li><b>Only READY rows are ever touched.</b> The drain re-checks
 *       status at claim time — an overlapping run may have sent the row
 *       since its id was listed.</li>
 *   <li><b>The error bookkeeping can never 1406.</b> Production
 *       sql_mode is STRICT_TRANS_TABLES; an over-long last_error would
 *       turn one failure into another.</li>
 * </ul>
 */
class MailResourceDrainPolicyTest {

    @Test
    void readyMailWithAttemptsLeft_isSent() {
        assertEquals(ClaimOutcome.SEND, MailResource.claimOutcome(MailStatus.READY, 0));
        assertEquals(ClaimOutcome.SEND, MailResource.claimOutcome(MailStatus.READY,
                MailResource.MAX_ATTEMPTS - 1));
    }

    @Test
    void readyMailAtTheAttemptCeiling_isParkedNotRetried() {
        assertEquals(ClaimOutcome.PARK_FAILED,
                MailResource.claimOutcome(MailStatus.READY, MailResource.MAX_ATTEMPTS));
        // Beyond the ceiling (a crash between increment and bookkeeping
        // can overshoot): still parked, never retried.
        assertEquals(ClaimOutcome.PARK_FAILED,
                MailResource.claimOutcome(MailStatus.READY, MailResource.MAX_ATTEMPTS + 3));
    }

    @Test
    void nonReadyRows_areNeverTouched() {
        assertEquals(ClaimOutcome.SKIP, MailResource.claimOutcome(MailStatus.SENT, 0));
        assertEquals(ClaimOutcome.SKIP, MailResource.claimOutcome(MailStatus.FAILED, 99));
        assertEquals(ClaimOutcome.SKIP, MailResource.claimOutcome(null, 0));
    }

    @Test
    void failureBeforeTheCeiling_staysReadyForTheNextRun() {
        assertEquals(MailStatus.READY,
                MailResource.statusAfterFailure(MailResource.MAX_ATTEMPTS - 1));
    }

    @Test
    void failureAtTheCeiling_parksTheMail() {
        assertEquals(MailStatus.FAILED,
                MailResource.statusAfterFailure(MailResource.MAX_ATTEMPTS));
    }

    @Test
    void errorText_carriesClassAndMessage() {
        assertEquals("IllegalStateException: SMTP 554 rejected",
                MailResource.truncatedError(
                        new IllegalStateException("SMTP 554 rejected"), 500));
    }

    @Test
    void errorText_survivesAMessagelessException() {
        assertEquals("NullPointerException",
                MailResource.truncatedError(new NullPointerException(), 500));
    }

    @Test
    void errorText_isTruncatedToTheColumn() {
        String text = MailResource.truncatedError(
                new RuntimeException("x".repeat(2000)), MailResource.LAST_ERROR_MAX_LENGTH);
        assertTrue(text.length() <= MailResource.LAST_ERROR_MAX_LENGTH);
    }
}
