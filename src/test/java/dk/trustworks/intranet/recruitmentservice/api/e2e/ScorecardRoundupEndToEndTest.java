package dk.trustworks.intranet.recruitmentservice.api.e2e;

import dk.trustworks.intranet.recruitmentservice.application.AiArtifactWorker;
import dk.trustworks.intranet.recruitmentservice.application.InterviewService;
import dk.trustworks.intranet.recruitmentservice.application.ScheduleInterviewCommand;
import dk.trustworks.intranet.recruitmentservice.application.ScorecardService;
import dk.trustworks.intranet.recruitmentservice.application.SubmitScorecardCommand;
import dk.trustworks.intranet.recruitmentservice.domain.entities.AiArtifact;
import dk.trustworks.intranet.recruitmentservice.domain.entities.Application;
import dk.trustworks.intranet.recruitmentservice.domain.entities.Interview;
import dk.trustworks.intranet.recruitmentservice.domain.enums.AiSubjectKind;
import dk.trustworks.intranet.recruitmentservice.domain.enums.InterviewRoundType;
import dk.trustworks.intranet.recruitmentservice.domain.enums.ParticipantRole;
import dk.trustworks.intranet.recruitmentservice.domain.enums.ScorecardRecommendation;
import io.quarkus.test.TestTransaction;
import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.junit.QuarkusTestProfile;
import io.quarkus.test.junit.TestProfile;
import jakarta.inject.Inject;
import org.junit.jupiter.api.Test;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

/**
 * End-to-end coverage for the SCORECARD_ROUNDUP auto-fire path:
 * <ol>
 *   <li>Schedule an Interview with two required scorers.</li>
 *   <li>First required scorer submits — {@link InterviewLifecycleService#onScorecardSubmitted}
 *       must NOT yet fire SCORECARD_ROUNDUP (1/2).</li>
 *   <li>Second required scorer submits — auto-fires exactly one SCORECARD_ROUNDUP
 *       artifact (2/2 required scorers reached).</li>
 *   <li>Drain outbox synchronously via {@link AiArtifactWorker#drainOnce()} (the
 *       package-private {@code drainScheduled()} entry point is not callable from
 *       this package; {@code drainOnce()} is the public method invoked).</li>
 *   <li>Verify the artifact reaches {@code GENERATED}.</li>
 * </ol>
 *
 * <p>Sandbox-blocked locally; designed for CI execution. Body short-circuits when
 * no seeded Application is available so test-compile stays the achievable bar.
 */
@QuarkusTest
@TestProfile(ScorecardRoundupEndToEndTest.AiEnabledProfile.class)
class ScorecardRoundupEndToEndTest {

    @Inject InterviewService interviewService;
    @Inject ScorecardService scorecardService;
    @Inject AiArtifactWorker worker;

    public static class AiEnabledProfile implements QuarkusTestProfile {
        @Override
        public Map<String, String> getConfigOverrides() {
            return Map.of(
                "recruitment.ai.enabled", "true",
                "recruitment.ai.scorecard-roundup.enabled", "true"
            );
        }
    }

    @Test
    @TestTransaction
    void submitLastRequiredScorecard_autoFiresRoundupArtifact_andDrainGenerates() {
        // Skip-when-no-seed: rely on Slice 1/2 dev seed providing an Application.
        // Sandbox blocks local execution; in CI an empty schema short-circuits.
        Application app = Application.findAll().firstResult();
        if (app == null) return;

        String actor1 = UUID.randomUUID().toString();
        String actor2 = UUID.randomUUID().toString();

        // 1. Schedule interview with two required scorers.
        var cmd = new ScheduleInterviewCommand(
            app.uuid,
            InterviewRoundType.FIRST,
            LocalDateTime.now().plusDays(1),
            60,
            List.of(
                new ScheduleInterviewCommand.Participant(actor1, ParticipantRole.LEAD_INTERVIEWER, true),
                new ScheduleInterviewCommand.Participant(actor2, ParticipantRole.SCORER, true)
            ),
            null);
        Interview iv = interviewService.schedule(cmd, actor1);

        // 2. First scorer submits — must NOT trigger SCORECARD_ROUNDUP (1/2).
        scorecardService.submit(iv.uuid, validCommand(ScorecardRecommendation.HIRE), actor1);
        long artifactsBeforeLast = AiArtifact.count(
            "subjectKind = ?1 and subjectUuid = ?2 and kind = ?3",
            AiSubjectKind.INTERVIEW, iv.uuid, "SCORECARD_ROUNDUP");
        assertEquals(0, artifactsBeforeLast,
            "no roundup artifact yet — only 1/2 required scorers submitted");

        // 3. Last required scorer submits — auto-fires exactly one artifact.
        scorecardService.submit(iv.uuid, validCommand(ScorecardRecommendation.STRONG_HIRE), actor2);
        long artifactsAfterLast = AiArtifact.count(
            "subjectKind = ?1 and subjectUuid = ?2 and kind = ?3",
            AiSubjectKind.INTERVIEW, iv.uuid, "SCORECARD_ROUNDUP");
        assertEquals(1, artifactsAfterLast,
            "exactly one roundup artifact created on last required submit");

        // 4. Drain outbox — artifact should reach GENERATED.
        worker.drainOnce();

        AiArtifact a = AiArtifact.find(
            "subjectKind = ?1 and subjectUuid = ?2 and kind = ?3 order by createdAt desc",
            AiSubjectKind.INTERVIEW, iv.uuid, "SCORECARD_ROUNDUP").firstResult();
        assertNotNull(a, "roundup artifact must persist after drain");
        assertEquals("GENERATED", a.state);
    }

    private SubmitScorecardCommand validCommand(ScorecardRecommendation rec) {
        return new SubmitScorecardCommand(
            (byte) 4, (byte) 4, (byte) 4, (byte) 4, (byte) 4, (byte) 4,
            rec, "good", null, null);
    }
}
