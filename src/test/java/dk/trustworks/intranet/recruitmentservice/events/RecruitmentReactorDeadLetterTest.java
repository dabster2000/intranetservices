package dk.trustworks.intranet.recruitmentservice.events;

import org.junit.jupiter.api.Test;

import java.io.IOException;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTimeoutPreemptively;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * V490: the pure half of the dead-letter record — turning whatever a failed
 * delivery threw into the two columns an operator actually reads.
 * <p>
 * DB-free by construction (the DB-backed half is exercised by
 * {@code RecruitmentReactorIntegrationTest}), so this runs in the fast tier
 * that gates deploys.
 */
class RecruitmentReactorDeadLetterTest {

    /**
     * The exact shape of the 2026-08-11 production failure: the reactor sees
     * a transaction wrapper whose own message is noise, and the useful text
     * — which channel, which Slack error — is one level down.
     */
    @Test
    void describeCause_unwrapsTheTransactionWrapperToTheRealFailure() {
        IOException root = new IOException(
                "Slack root-card post failed for channel C0BNQP76M1D: channel_not_found");
        RuntimeException wrapper = new RuntimeException("java.io.IOException: " + root.getMessage(), root);

        RecruitmentReactorDeadLetter.Cause cause = RecruitmentReactorDeadLetter.describeCause(wrapper);

        assertEquals("java.io.IOException", cause.errorClass());
        assertEquals("Slack root-card post failed for channel C0BNQP76M1D: channel_not_found",
                cause.errorMessage());
    }

    @Test
    void describeCause_walksAChainDeeperThanOne() {
        IOException root = new IOException("channel_not_found");
        Throwable middle = new IllegalStateException("delivery failed", root);
        Throwable outer = new RuntimeException("transaction rolled back", middle);

        assertEquals("java.io.IOException",
                RecruitmentReactorDeadLetter.describeCause(outer).errorClass());
    }

    @Test
    void describeCause_keepsAnExceptionThatHasNoCause() {
        RecruitmentReactorDeadLetter.Cause cause =
                RecruitmentReactorDeadLetter.describeCause(new IllegalArgumentException("bad payload"));

        assertEquals("java.lang.IllegalArgumentException", cause.errorClass());
        assertEquals("bad payload", cause.errorMessage());
    }

    @Test
    void describeCause_toleratesNoMessage() {
        RecruitmentReactorDeadLetter.Cause cause =
                RecruitmentReactorDeadLetter.describeCause(new IllegalStateException());

        assertEquals("java.lang.IllegalStateException", cause.errorClass());
        assertNull(cause.errorMessage());
    }

    @Test
    void describeCause_nullFailureIsNotAnError() {
        RecruitmentReactorDeadLetter.Cause cause = RecruitmentReactorDeadLetter.describeCause(null);

        assertNull(cause.errorClass());
        assertNull(cause.errorMessage());
    }

    /**
     * markSkipped() runs on the sweep thread. A driver exception whose cause
     * chain loops must not spin it — the reactor would stop delivering every
     * later event.
     */
    @Test
    void describeCause_terminatesOnASelfReferencingCauseChain() {
        assertTimeoutPreemptively(java.time.Duration.ofSeconds(5), () -> {
            RecruitmentReactorDeadLetter.Cause cause =
                    RecruitmentReactorDeadLetter.describeCause(new SelfCausingException());
            assertEquals(SelfCausingException.class.getName(), cause.errorClass());
        });
    }

    /**
     * The 32-step bound stops before the true root of a pathologically deep
     * chain. That is the intended trade: an operator gains nothing from
     * unwrapping 500 wrappers, and the walk must stay bounded.
     */
    @Test
    void describeCause_stopsAtTheBoundOnAPathologicallyDeepChain() {
        Throwable chain = new IllegalStateException("the true root");
        for (int i = 0; i < 500; i++) {
            chain = new RuntimeException("layer " + i, chain);
        }
        final Throwable deep = chain;

        RecruitmentReactorDeadLetter.Cause cause = RecruitmentReactorDeadLetter.describeCause(deep);

        // Stopped 32 levels down from "layer 499", nowhere near the root.
        assertEquals("java.lang.RuntimeException", cause.errorClass());
        assertEquals("layer 467", cause.errorMessage());
    }

    @Test
    void describeCause_truncatesAMessageToTheColumnWidth() {
        String huge = "x".repeat(RecruitmentReactorDeadLetter.MESSAGE_MAX_CHARS + 500);

        RecruitmentReactorDeadLetter.Cause cause =
                RecruitmentReactorDeadLetter.describeCause(new IllegalStateException(huge));

        assertEquals(RecruitmentReactorDeadLetter.MESSAGE_MAX_CHARS, cause.errorMessage().length());
        assertTrue(huge.startsWith(cause.errorMessage()));
    }

    @Test
    void truncate_passesShortAndNullValuesThrough() {
        assertNull(RecruitmentReactorDeadLetter.truncate(null, 10));
        assertEquals("short", RecruitmentReactorDeadLetter.truncate("short", 10));
        assertEquals("exactlyten", RecruitmentReactorDeadLetter.truncate("exactlyten", 10));
        assertEquals("exactlyten", RecruitmentReactorDeadLetter.truncate("exactlytenPLUS", 10));
    }

    /** getCause() returning this — what initCause() refuses to let you build. */
    private static final class SelfCausingException extends RuntimeException {
        SelfCausingException() {
            super("loops back on itself");
        }

        @Override
        public synchronized Throwable getCause() {
            return this;
        }
    }
}
