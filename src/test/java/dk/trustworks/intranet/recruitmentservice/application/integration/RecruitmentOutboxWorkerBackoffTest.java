package dk.trustworks.intranet.recruitmentservice.application.integration;

import org.junit.jupiter.api.Test;

import java.time.Duration;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class RecruitmentOutboxWorkerBackoffTest {

    @Test
    void backoff_attempt_1_is_1_minute() {
        assertEquals(Duration.ofMinutes(1), RecruitmentOutboxWorker.backoffFor(1));
    }

    @Test
    void backoff_attempt_2_is_5_minutes() {
        assertEquals(Duration.ofMinutes(5), RecruitmentOutboxWorker.backoffFor(2));
    }

    @Test
    void backoff_attempt_3_is_30_minutes() {
        assertEquals(Duration.ofMinutes(30), RecruitmentOutboxWorker.backoffFor(3));
    }

    @Test
    void backoff_attempt_4_or_more_is_2_hours() {
        assertEquals(Duration.ofHours(2), RecruitmentOutboxWorker.backoffFor(4));
        assertEquals(Duration.ofHours(2), RecruitmentOutboxWorker.backoffFor(5));
        assertEquals(Duration.ofHours(2), RecruitmentOutboxWorker.backoffFor(99));
    }

    @Test
    void terminal_at_5_or_more_attempts() {
        assertFalse(RecruitmentOutboxWorker.isTerminalAt(1));
        assertFalse(RecruitmentOutboxWorker.isTerminalAt(4));
        assertTrue(RecruitmentOutboxWorker.isTerminalAt(5));
        assertTrue(RecruitmentOutboxWorker.isTerminalAt(6));
    }
}
