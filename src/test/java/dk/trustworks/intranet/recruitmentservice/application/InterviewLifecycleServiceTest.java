package dk.trustworks.intranet.recruitmentservice.application;

import dk.trustworks.intranet.recruitmentservice.domain.entities.Application;
import dk.trustworks.intranet.recruitmentservice.domain.entities.Interview;
import dk.trustworks.intranet.recruitmentservice.domain.entities.OpenRole;
import dk.trustworks.intranet.recruitmentservice.domain.enums.AiArtifactKind;
import dk.trustworks.intranet.recruitmentservice.domain.enums.AiSubjectKind;
import dk.trustworks.intranet.recruitmentservice.domain.enums.ApplicationStage;
import dk.trustworks.intranet.recruitmentservice.domain.enums.InterviewRoundType;
import dk.trustworks.intranet.recruitmentservice.domain.enums.RoleStatus;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

class InterviewLifecycleServiceTest {

    @Mock OpenRoleService openRoleService;
    @Mock ApplicationService applicationService;
    @Mock AiArtifactService aiArtifactService;
    @Mock InterviewParticipantQuery participantQuery;
    @Mock ScorecardQuery scorecardQuery;
    @InjectMocks InterviewLifecycleService svc;

    private AutoCloseable mocks;

    @BeforeEach
    void init() {
        mocks = MockitoAnnotations.openMocks(this);
    }

    @Test
    void onScheduled_advancesRoleToInterviewing_ifSourcing() {
        Interview iv = new Interview();
        iv.uuid = "iv-1";
        iv.applicationUuid = "app-1";
        iv.roundType = InterviewRoundType.FIRST;
        Application app = new Application();
        app.uuid = "app-1";
        app.roleUuid = "role-1";
        app.stage = ApplicationStage.SCREENING;
        OpenRole role = new OpenRole();
        role.uuid = "role-1";
        role.status = RoleStatus.SOURCING;

        when(applicationService.findByIdOrNull("app-1")).thenReturn(app);
        when(openRoleService.findByIdOrNull("role-1")).thenReturn(role);

        svc.onScheduled(iv, "actor-1");

        verify(openRoleService).advanceToInterviewingIfSourcing(role, "actor-1");
        verify(applicationService).advanceStageForwardOnly(app, ApplicationStage.FIRST_INTERVIEW, "actor-1");
    }

    @Test
    void onScheduled_doesNotAdvance_ifRoleAlreadyInterviewing() {
        Interview iv = new Interview();
        iv.uuid = "iv-1";
        iv.applicationUuid = "app-1";
        iv.roundType = InterviewRoundType.FINAL;
        Application app = new Application();
        app.uuid = "app-1";
        app.roleUuid = "role-1";
        app.stage = ApplicationStage.FINAL_INTERVIEW;
        OpenRole role = new OpenRole();
        role.uuid = "role-1";
        role.status = RoleStatus.INTERVIEWING;

        when(applicationService.findByIdOrNull("app-1")).thenReturn(app);
        when(openRoleService.findByIdOrNull("role-1")).thenReturn(role);

        svc.onScheduled(iv, "actor-1");

        // Role hook is invoked unconditionally; the service itself short-circuits when not SOURCING.
        verify(openRoleService).advanceToInterviewingIfSourcing(role, "actor-1");
        // Application hook is invoked with FINAL_INTERVIEW; service itself short-circuits when at-or-past.
        verify(applicationService).advanceStageForwardOnly(app, ApplicationStage.FINAL_INTERVIEW, "actor-1");
    }

    @Test
    void onScheduled_specialRoundType_doesNotChangeApplicationStage() {
        Interview iv = new Interview();
        iv.uuid = "iv-1";
        iv.applicationUuid = "app-1";
        iv.roundType = InterviewRoundType.SPECIAL;
        Application app = new Application();
        app.uuid = "app-1";
        app.roleUuid = "role-1";
        app.stage = ApplicationStage.SCREENING;
        OpenRole role = new OpenRole();
        role.uuid = "role-1";
        role.status = RoleStatus.SOURCING;

        when(applicationService.findByIdOrNull("app-1")).thenReturn(app);
        when(openRoleService.findByIdOrNull("role-1")).thenReturn(role);

        svc.onScheduled(iv, "actor-1");

        verify(openRoleService).advanceToInterviewingIfSourcing(role, "actor-1");
        verify(applicationService, never()).advanceStageForwardOnly(any(), any(), any());
    }

    @Test
    void onScorecardSubmitted_firesArtifact_whenAllRequiredSubmitted() {
        Interview iv = new Interview();
        iv.uuid = "iv-1";
        when(participantQuery.requiredScorerCount("iv-1")).thenReturn(2L);
        when(scorecardQuery.submittedCount("iv-1")).thenReturn(2L);

        svc.onScorecardSubmitted(iv, "actor-1");

        verify(aiArtifactService).requestArtifact(
            eq(AiSubjectKind.INTERVIEW),
            eq("iv-1"),
            eq(AiArtifactKind.SCORECARD_ROUNDUP),
            any(),
            eq("actor-1"));
    }

    @Test
    void onScorecardSubmitted_doesNotFire_whenSomeRequiredMissing() {
        Interview iv = new Interview();
        iv.uuid = "iv-1";
        when(participantQuery.requiredScorerCount("iv-1")).thenReturn(3L);
        when(scorecardQuery.submittedCount("iv-1")).thenReturn(2L);

        svc.onScorecardSubmitted(iv, "actor-1");

        verifyNoInteractions(aiArtifactService);
    }
}
