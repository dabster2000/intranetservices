package dk.trustworks.intranet.recruitmentservice.application;

import dk.trustworks.intranet.recruitmentservice.application.integration.RecruitmentOutboxService;
import dk.trustworks.intranet.recruitmentservice.application.integration.payloads.OutlookCancelPayload;
import dk.trustworks.intranet.recruitmentservice.application.integration.payloads.OutlookCreatePayload;
import dk.trustworks.intranet.recruitmentservice.application.integration.payloads.OutlookUpdatePayload;
import dk.trustworks.intranet.recruitmentservice.domain.entities.Application;
import dk.trustworks.intranet.recruitmentservice.domain.entities.Interview;
import dk.trustworks.intranet.recruitmentservice.domain.entities.InterviewParticipant;
import dk.trustworks.intranet.recruitmentservice.domain.entities.OpenRole;
import dk.trustworks.intranet.recruitmentservice.domain.enums.InterviewRoundType;
import dk.trustworks.intranet.recruitmentservice.domain.integration.OutboxKind;
import dk.trustworks.intranet.userservice.model.Employee;
import org.junit.jupiter.api.Test;

import java.time.LocalDateTime;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class InterviewLifecycleServiceOutboxTest {

    private static InterviewLifecycleService withDirectory(Map<String, Employee> directory,
                                                           Map<String, List<InterviewParticipant>> participantsByIv) {
        InterviewLifecycleService svc = new InterviewLifecycleService() {
            @Override Employee findEmployee(String uuid) { return directory.get(uuid); }
            @Override List<InterviewParticipant> listParticipants(String interviewUuid) {
                return participantsByIv.getOrDefault(interviewUuid, List.of());
            }
        };
        svc.outboxService = mock(RecruitmentOutboxService.class);
        svc.deepLinkBase = "https://intra.trustworks.dk";
        svc.openRoleService = mock(OpenRoleService.class);
        svc.applicationService = mock(ApplicationService.class);
        svc.aiArtifactService = mock(AiArtifactService.class);
        svc.participantQuery = mock(InterviewParticipantQuery.class);
        svc.scorecardQuery = mock(ScorecardQuery.class);
        return svc;
    }

    @Test
    void onScheduled_enqueues_outlook_create_with_canonical_key() {
        Interview iv = new Interview();
        iv.uuid = "iv-1";
        iv.applicationUuid = "app-1";
        iv.scheduledAt = LocalDateTime.parse("2026-05-01T09:00:00");
        iv.durationMinutes = 60;
        iv.roundNumber = 1;
        iv.roundType = InterviewRoundType.FIRST;

        Employee tam = new Employee();
        tam.setUuid("u-organizer");
        tam.setEmail("tam@trustworks.dk");

        Map<String, Employee> dir = new HashMap<>();
        dir.put("u-organizer", tam);

        InterviewLifecycleService svc = withDirectory(dir, new HashMap<>());
        // application + role lookups return null (skipped via guards) so we focus on outbox enqueue
        when(svc.applicationService.findByIdOrNull("app-1")).thenReturn(new Application());

        svc.onScheduled(iv, "u-organizer");

        verify(svc.outboxService).enqueue(
                eq(OutboxKind.OUTLOOK_EVENT_CREATE),
                eq("interview:iv-1"),
                eq("iv-1"),
                any(OutlookCreatePayload.class));
    }

    @Test
    void onRescheduled_enqueues_versioned_update() {
        Interview iv = new Interview();
        iv.uuid = "iv-1";
        iv.outlookEventId = "evt-1";
        iv.scheduledAt = LocalDateTime.now();
        iv.durationMinutes = 60;

        Employee tam = new Employee();
        tam.setUuid("u-org");
        tam.setEmail("tam@trustworks.dk");

        Map<String, Employee> dir = new HashMap<>();
        dir.put("u-org", tam);

        InterviewLifecycleService svc = withDirectory(dir, new HashMap<>());

        svc.onRescheduled(iv, 2, "u-org");

        verify(svc.outboxService).enqueue(
                eq(OutboxKind.OUTLOOK_EVENT_UPDATE),
                eq("interview:iv-1:v2"),
                eq("iv-1"),
                any(OutlookUpdatePayload.class));
    }

    @Test
    void onCancelled_enqueues_cancel_with_canonical_key() {
        Interview iv = new Interview();
        iv.uuid = "iv-1";
        iv.outlookEventId = "evt-1";

        Employee tam = new Employee();
        tam.setUuid("u-org");
        tam.setEmail("tam@trustworks.dk");

        Map<String, Employee> dir = new HashMap<>();
        dir.put("u-org", tam);

        InterviewLifecycleService svc = withDirectory(dir, new HashMap<>());

        svc.onCancelled(iv, "Candidate withdrew", "u-org");

        verify(svc.outboxService).enqueue(
                eq(OutboxKind.OUTLOOK_EVENT_CANCEL),
                eq("interview:iv-1:cancel"),
                eq("iv-1"),
                any(OutlookCancelPayload.class));
    }

    @Test
    void onRescheduled_skipped_when_outlookEventId_missing() {
        Interview iv = new Interview();
        iv.uuid = "iv-2";
        iv.outlookEventId = null;
        iv.scheduledAt = LocalDateTime.now();
        iv.durationMinutes = 60;

        Employee tam = new Employee();
        tam.setUuid("u-org");
        tam.setEmail("tam@trustworks.dk");

        Map<String, Employee> dir = new HashMap<>();
        dir.put("u-org", tam);

        InterviewLifecycleService svc = withDirectory(dir, new HashMap<>());
        svc.onRescheduled(iv, 1, "u-org");

        org.mockito.Mockito.verifyNoInteractions(svc.outboxService);
    }
}
