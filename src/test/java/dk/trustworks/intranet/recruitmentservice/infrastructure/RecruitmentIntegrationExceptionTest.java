package dk.trustworks.intranet.recruitmentservice.infrastructure;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

class RecruitmentIntegrationExceptionTest {

    @Test
    void outlook_retryable_carries_metadata() {
        OutlookCalendarException ex = new OutlookCalendarException(true, "GRAPH_429", "rate limited");
        assertTrue(ex.isRetryable());
        assertEquals("GRAPH_429", ex.getErrorCode());
        assertEquals("rate limited", ex.getDetail());
    }

    @Test
    void slack_terminal_carries_metadata() {
        SlackException ex = new SlackException(false, "user_not_found", "U123");
        assertFalse(ex.isRetryable());
        assertEquals("user_not_found", ex.getErrorCode());
        assertEquals("U123", ex.getDetail());
    }

    @Test
    void exception_chain_preserves_cause() {
        RuntimeException cause = new RuntimeException("root");
        OutlookCalendarException ex = new OutlookCalendarException(true, "X", "y", cause);
        assertSame(cause, ex.getCause());
    }
}
