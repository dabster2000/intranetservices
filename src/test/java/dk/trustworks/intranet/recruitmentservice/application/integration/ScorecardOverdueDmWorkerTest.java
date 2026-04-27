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
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;

/**
 * Unit tests for {@link ScorecardOverdueDmWorker}.
 *
 * <p>Pattern: subclass + override the package-private Panache seams; install a
 * fixed {@link Clock} for deterministic stage selection. Mockito cannot stub
 * Panache statics, so this is the project's canonical seam pattern.
 */
class ScorecardOverdueDmWorkerTest {

    private static Interview heldInterview(String uuid, LocalDateTime heldAt) {
        Interview iv = new Interview();
        iv.uuid = uuid;
        iv.applicationUuid = "app-1";
        iv.scheduledAt = heldAt; // not material to this worker
        iv.heldAt = heldAt;
        iv.durationMinutes = 60;
        iv.status = InterviewStatus.HELD;
        return iv;
    }

    private static InterviewParticipant requiredScorer(String userUuid) {
        InterviewParticipant p = new InterviewParticipant();
        p.uuid = "pp-" + userUuid;
        p.interviewUuid = "iv-1";
        p.userUuid = userUuid;
        p.isRequiredScorer = Boolean.TRUE;
        return p;
    }

    @Test
    void stage_1_at_24h_after_held_at() {
        LocalDateTime heldAt = LocalDateTime.of(2026, 5, 1, 10, 0);
        // 25h after heldAt => past 24h cutoff, but NOT past 48h cutoff.
        Clock fixed = Clock.fixed(
                heldAt.plusHours(25).toInstant(ZoneOffset.UTC),
                ZoneOffset.UTC);

        Interview iv = heldInterview("iv-1", heldAt);
        InterviewParticipant scorer = requiredScorer("u-1");

        RecruitmentOutboxService outbox = mock(RecruitmentOutboxService.class);

        ScorecardOverdueDmWorker worker = new ScorecardOverdueDmWorker() {
            @Override
            List<Interview> listHeldOverdue(LocalDateTime cutoff24h) {
                assertTrue(!iv.heldAt.isAfter(cutoff24h),
                        "interview heldAt must be at or before cutoff24h to be returned");
                return List.of(iv);
            }

            @Override
            List<InterviewParticipant> listRequiredScorers(String interviewUuid) {
                assertEquals("iv-1", interviewUuid);
                return List.of(scorer);
            }

            @Override
            Set<String> listScorecardSubmitterUuids(String interviewUuid) {
                return Set.of(); // nobody has submitted
            }
        };
        worker.outboxService = outbox;
        worker.deepLinkBase = "https://intra.trustworks.dk";
        worker.clock = fixed;

        worker.enqueueOverdueDms();

        ArgumentCaptor<OutboxKind> kindCap = ArgumentCaptor.forClass(OutboxKind.class);
        ArgumentCaptor<String> keyCap = ArgumentCaptor.forClass(String.class);
        ArgumentCaptor<String> relCap = ArgumentCaptor.forClass(String.class);
        ArgumentCaptor<Object> payloadCap = ArgumentCaptor.forClass(Object.class);
        verify(outbox, times(1)).enqueue(
                kindCap.capture(), keyCap.capture(), relCap.capture(), payloadCap.capture());

        assertEquals(OutboxKind.SLACK_SCORECARD_OVERDUE_DM, kindCap.getValue());
        assertEquals("overdue:iv-1:u-1:stage1", keyCap.getValue());
        assertEquals("iv-1", relCap.getValue());

        assertTrue(payloadCap.getValue() instanceof SlackDmPayload);
        SlackDmPayload dm = (SlackDmPayload) payloadCap.getValue();
        assertEquals("u-1", dm.recipientUserUuid());
        assertEquals("Reminder: scorecard due", dm.headline());
        assertTrue(dm.deepLinkUrl().endsWith("/recruitment/interviews/iv-1"));
    }

    @Test
    void stage_2_at_48h_after_held_at() {
        LocalDateTime heldAt = LocalDateTime.of(2026, 5, 1, 10, 0);
        // 49h after heldAt => past 48h cutoff => stage 2.
        Clock fixed = Clock.fixed(
                heldAt.plusHours(49).toInstant(ZoneOffset.UTC),
                ZoneOffset.UTC);

        Interview iv = heldInterview("iv-1", heldAt);
        InterviewParticipant scorer = requiredScorer("u-1");

        RecruitmentOutboxService outbox = mock(RecruitmentOutboxService.class);

        ScorecardOverdueDmWorker worker = new ScorecardOverdueDmWorker() {
            @Override List<Interview> listHeldOverdue(LocalDateTime cutoff24h) { return List.of(iv); }
            @Override List<InterviewParticipant> listRequiredScorers(String interviewUuid) { return List.of(scorer); }
            @Override Set<String> listScorecardSubmitterUuids(String interviewUuid) { return Set.of(); }
        };
        worker.outboxService = outbox;
        worker.deepLinkBase = "https://intra.trustworks.dk";
        worker.clock = fixed;

        worker.enqueueOverdueDms();

        ArgumentCaptor<String> keyCap = ArgumentCaptor.forClass(String.class);
        ArgumentCaptor<Object> payloadCap = ArgumentCaptor.forClass(Object.class);
        verify(outbox, times(1)).enqueue(
                ArgumentCaptor.forClass(OutboxKind.class).capture(),
                keyCap.capture(),
                ArgumentCaptor.forClass(String.class).capture(),
                payloadCap.capture());

        assertEquals("overdue:iv-1:u-1:stage2", keyCap.getValue());
        SlackDmPayload dm = (SlackDmPayload) payloadCap.getValue();
        assertEquals("Overdue: scorecard >48h", dm.headline());
        assertTrue(dm.bodyMarkdown().contains("49h overdue"),
                "stage 2 body should include hours-overdue: " + dm.bodyMarkdown());
    }

    @Test
    void skips_scorers_who_already_submitted() {
        LocalDateTime heldAt = LocalDateTime.of(2026, 5, 1, 10, 0);
        Clock fixed = Clock.fixed(
                heldAt.plusHours(25).toInstant(ZoneOffset.UTC),
                ZoneOffset.UTC);

        Interview iv = heldInterview("iv-1", heldAt);
        InterviewParticipant scorer = requiredScorer("u-1");

        RecruitmentOutboxService outbox = mock(RecruitmentOutboxService.class);

        ScorecardOverdueDmWorker worker = new ScorecardOverdueDmWorker() {
            @Override List<Interview> listHeldOverdue(LocalDateTime cutoff24h) { return List.of(iv); }
            @Override List<InterviewParticipant> listRequiredScorers(String interviewUuid) { return List.of(scorer); }
            @Override Set<String> listScorecardSubmitterUuids(String interviewUuid) { return Set.of("u-1"); }
        };
        worker.outboxService = outbox;
        worker.deepLinkBase = "https://intra.trustworks.dk";
        worker.clock = fixed;

        worker.enqueueOverdueDms();

        verifyNoInteractions(outbox);
    }
}
