package dk.trustworks.intranet.recruitmentservice.application.integration;

import dk.trustworks.intranet.recruitmentservice.application.integration.payloads.SlackDmPayload;
import dk.trustworks.intranet.recruitmentservice.domain.entities.Interview;
import dk.trustworks.intranet.recruitmentservice.domain.entities.InterviewParticipant;
import dk.trustworks.intranet.recruitmentservice.domain.enums.InterviewStatus;
import dk.trustworks.intranet.recruitmentservice.domain.integration.OutboxKind;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.time.Clock;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

/**
 * Unit tests for {@link InterviewTomorrowDmWorker}.
 *
 * <p>Pattern: subclass the worker to override the package-private Panache seams,
 * and inject a {@link Clock#fixed} so the "tomorrow" calculation is deterministic.
 * The plan-mandated approach because Mockito cannot stub Panache statics.
 */
class InterviewTomorrowDmWorkerTest {

    @Test
    void enqueues_one_dm_per_participant_for_tomorrow() {
        // Fixed wall-clock at 2026-05-01 06:00 UTC. In Europe/Copenhagen (CEST = UTC+2)
        // that is 2026-05-01 08:00 local => "tomorrow" in CET = 2026-05-02.
        Clock fixed = Clock.fixed(
                LocalDateTime.of(2026, 5, 1, 6, 0).toInstant(ZoneOffset.UTC),
                ZoneOffset.UTC);

        // Interview scheduled at 2026-05-02 07:00 UTC = 09:00 CET (a Saturday).
        Interview iv = new Interview();
        iv.uuid = "iv-1";
        iv.applicationUuid = "app-1";
        iv.scheduledAt = LocalDateTime.of(2026, 5, 2, 7, 0);
        iv.durationMinutes = 45;
        iv.status = InterviewStatus.SCHEDULED;

        InterviewParticipant p1 = new InterviewParticipant();
        p1.uuid = "pp-1";
        p1.interviewUuid = "iv-1";
        p1.userUuid = "u-1";
        InterviewParticipant p2 = new InterviewParticipant();
        p2.uuid = "pp-2";
        p2.interviewUuid = "iv-1";
        p2.userUuid = "u-2";

        RecruitmentOutboxService outbox = mock(RecruitmentOutboxService.class);

        InterviewTomorrowDmWorker worker = new InterviewTomorrowDmWorker() {
            @Override
            List<Interview> listScheduledInWindow(LocalDateTime startUtc, LocalDateTime endUtc) {
                // Sanity: window must bracket the scheduledAt.
                assertTrue(!iv.scheduledAt.isBefore(startUtc) && iv.scheduledAt.isBefore(endUtc),
                        "interview scheduledAt must fall inside the [startUtc, endUtc) window");
                return List.of(iv);
            }

            @Override
            List<InterviewParticipant> listParticipants(String interviewUuid) {
                assertEquals("iv-1", interviewUuid);
                return List.of(p1, p2);
            }
        };
        worker.outboxService = outbox;
        worker.deepLinkBase = "https://intra.trustworks.dk";
        worker.clock = fixed;

        worker.enqueueTomorrowDms();

        ArgumentCaptor<OutboxKind> kindCap = ArgumentCaptor.forClass(OutboxKind.class);
        ArgumentCaptor<String> keyCap = ArgumentCaptor.forClass(String.class);
        ArgumentCaptor<String> relatedCap = ArgumentCaptor.forClass(String.class);
        ArgumentCaptor<Object> payloadCap = ArgumentCaptor.forClass(Object.class);

        verify(outbox, times(2)).enqueue(
                kindCap.capture(), keyCap.capture(), relatedCap.capture(), payloadCap.capture());

        // Both calls must use the SLACK_INTERVIEW_TOMORROW_DM kind.
        assertEquals(List.of(
                OutboxKind.SLACK_INTERVIEW_TOMORROW_DM,
                OutboxKind.SLACK_INTERVIEW_TOMORROW_DM), kindCap.getAllValues());

        // Idempotency keys must follow tomorrow:<interviewUuid>:... shape and embed the target date.
        for (String key : keyCap.getAllValues()) {
            assertTrue(key.startsWith("tomorrow:iv-1:"), "unexpected idempotency key: " + key);
            assertTrue(key.endsWith(":2026-05-02"), "key must embed tomorrow's CET date: " + key);
        }
        // And the per-recipient suffix must be present.
        assertTrue(keyCap.getAllValues().contains("tomorrow:iv-1:u-1:2026-05-02"));
        assertTrue(keyCap.getAllValues().contains("tomorrow:iv-1:u-2:2026-05-02"));

        // related_uuid is the interview uuid for both rows.
        assertEquals(List.of("iv-1", "iv-1"), relatedCap.getAllValues());

        // Payload type + recipient routing.
        for (Object payload : payloadCap.getAllValues()) {
            assertTrue(payload instanceof SlackDmPayload, "payload must be a SlackDmPayload");
            SlackDmPayload dm = (SlackDmPayload) payload;
            assertEquals("Reminder: you have an interview tomorrow.", dm.headline());
            assertTrue(dm.bodyMarkdown().contains("2026-05-02"), "body should include CET date");
            assertTrue(dm.bodyMarkdown().contains("09:00 CET"), "body should include CET start time");
            assertTrue(dm.bodyMarkdown().contains("(45 min)"), "body should include duration");
            assertTrue(dm.deepLinkUrl().endsWith("/recruitment/interviews/iv-1"));
        }
    }
}
