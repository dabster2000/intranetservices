package dk.trustworks.intranet.recruitmentservice.application.integration;

import org.junit.jupiter.api.Test;

import java.time.LocalDate;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class OutboxIdempotencyKeysTest {

    @Test
    void outlook_create_format_is_interview_colon_uuid() {
        assertEquals("interview:iv-1", OutboxIdempotencyKeys.outlookCreate("iv-1"));
    }

    @Test
    void outlook_update_format_includes_v_then_version() {
        assertEquals("interview:iv-1:v1", OutboxIdempotencyKeys.outlookUpdate("iv-1", 1));
        assertEquals("interview:iv-1:v3", OutboxIdempotencyKeys.outlookUpdate("iv-1", 3));
    }

    @Test
    void outlook_update_rejects_zero_or_negative_version() {
        assertThrows(IllegalArgumentException.class,
                () -> OutboxIdempotencyKeys.outlookUpdate("iv-1", 0));
        assertThrows(IllegalArgumentException.class,
                () -> OutboxIdempotencyKeys.outlookUpdate("iv-1", -1));
    }

    @Test
    void outlook_cancel_format_appends_cancel_suffix() {
        assertEquals("interview:iv-1:cancel", OutboxIdempotencyKeys.outlookCancel("iv-1"));
    }

    @Test
    void slack_interview_tomorrow_format_includes_recipient_and_iso_date() {
        assertEquals(
                "tomorrow:iv-1:user-7:2026-05-01",
                OutboxIdempotencyKeys.slackInterviewTomorrow("iv-1", "user-7", LocalDate.of(2026, 5, 1)));
    }

    @Test
    void slack_scorecard_overdue_format_includes_stage_token() {
        assertEquals(
                "overdue:iv-1:scorer-3:stage1",
                OutboxIdempotencyKeys.slackScorecardOverdue("iv-1", "scorer-3", 1));
        assertEquals(
                "overdue:iv-1:scorer-3:stage3",
                OutboxIdempotencyKeys.slackScorecardOverdue("iv-1", "scorer-3", 3));
    }

    @Test
    void slack_scorecard_overdue_rejects_zero_or_negative_stage() {
        assertThrows(IllegalArgumentException.class,
                () -> OutboxIdempotencyKeys.slackScorecardOverdue("iv-1", "scorer-3", 0));
    }

    @Test
    void rejects_blank_or_null_uuids() {
        assertThrows(IllegalArgumentException.class,
                () -> OutboxIdempotencyKeys.outlookCreate(null));
        assertThrows(IllegalArgumentException.class,
                () -> OutboxIdempotencyKeys.outlookCreate(""));
        assertThrows(IllegalArgumentException.class,
                () -> OutboxIdempotencyKeys.outlookCreate("  "));
        assertThrows(IllegalArgumentException.class,
                () -> OutboxIdempotencyKeys.slackInterviewTomorrow("iv-1", "", LocalDate.now()));
    }
}
