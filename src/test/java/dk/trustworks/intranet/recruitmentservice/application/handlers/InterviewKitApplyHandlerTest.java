package dk.trustworks.intranet.recruitmentservice.application.handlers;

import dk.trustworks.intranet.recruitmentservice.domain.entities.AiArtifact;
import dk.trustworks.intranet.recruitmentservice.domain.entities.Interview;
import dk.trustworks.intranet.recruitmentservice.domain.enums.AiArtifactKind;
import dk.trustworks.intranet.recruitmentservice.domain.enums.AiSubjectKind;
import dk.trustworks.intranet.recruitmentservice.domain.enums.InterviewRoundType;
import dk.trustworks.intranet.recruitmentservice.domain.enums.InterviewStatus;
import io.quarkus.test.TestTransaction;
import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import org.junit.jupiter.api.Test;

import java.time.LocalDateTime;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

@QuarkusTest
class InterviewKitApplyHandlerTest {

    @Inject InterviewKitApplyHandler handler;

    @Test
    void handlesInterviewKitKind() {
        assertTrue(handler.handles(AiArtifactKind.INTERVIEW_KIT));
        assertFalse(handler.handles(AiArtifactKind.SCORECARD_ROUNDUP));
        assertFalse(handler.handles(AiArtifactKind.ROLE_BRIEF));
    }

    @Test
    @TestTransaction
    void apply_setsInterviewKitArtifactUuid_whenNull() {
        Interview iv = new Interview();
        iv.uuid = UUID.randomUUID().toString();
        iv.applicationUuid = UUID.randomUUID().toString();
        iv.roundNumber = 1;
        iv.roundType = InterviewRoundType.FIRST;
        iv.scheduledAt = LocalDateTime.now().plusDays(1);
        iv.durationMinutes = 60;
        iv.status = InterviewStatus.SCHEDULED;
        iv.persist();

        AiArtifact a = new AiArtifact();
        a.uuid = UUID.randomUUID().toString();
        a.subjectKind = AiSubjectKind.INTERVIEW;
        a.subjectUuid = iv.uuid;
        a.kind = AiArtifactKind.INTERVIEW_KIT.name();
        a.promptVersion = "interview-kit-v1";
        a.model = "gpt-5-nano";
        a.inputDigest = "x".repeat(64);
        a.state = "REVIEWED";
        a.persist();

        handler.apply(a, /*overrideJson*/ null);

        Interview reloaded = Interview.findById(iv.uuid);
        assertEquals(a.uuid, reloaded.interviewKitArtifactUuid);
    }

    @Test
    @TestTransaction
    void apply_doesNotOverwrite_whenInterviewKitArtifactUuidAlreadySet() {
        String existingArtifactUuid = UUID.randomUUID().toString();
        Interview iv = new Interview();
        iv.uuid = UUID.randomUUID().toString();
        iv.applicationUuid = UUID.randomUUID().toString();
        iv.roundNumber = 1;
        iv.roundType = InterviewRoundType.FIRST;
        iv.scheduledAt = LocalDateTime.now().plusDays(1);
        iv.durationMinutes = 60;
        iv.status = InterviewStatus.SCHEDULED;
        iv.interviewKitArtifactUuid = existingArtifactUuid;
        iv.persist();

        AiArtifact a = new AiArtifact();
        a.uuid = UUID.randomUUID().toString();
        a.subjectKind = AiSubjectKind.INTERVIEW;
        a.subjectUuid = iv.uuid;
        a.kind = AiArtifactKind.INTERVIEW_KIT.name();
        a.promptVersion = "interview-kit-v1";
        a.model = "gpt-5-nano";
        a.inputDigest = "y".repeat(64);
        a.state = "REVIEWED";
        a.persist();

        handler.apply(a, /*overrideJson*/ null);

        Interview reloaded = Interview.findById(iv.uuid);
        assertEquals(existingArtifactUuid, reloaded.interviewKitArtifactUuid,
            "should not overwrite existing kit pointer");
    }
}
