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
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

@QuarkusTest
class ScorecardRoundupApplyHandlerTest {

    @Inject ScorecardRoundupApplyHandler handler;

    @Test
    void handlesScorecardRoundupKind() {
        assertTrue(handler.handles(AiArtifactKind.SCORECARD_ROUNDUP));
        assertFalse(handler.handles(AiArtifactKind.INTERVIEW_KIT));
    }

    @Test
    @TestTransaction
    void apply_isNoOp_doesNotMutateInterview() {
        Interview iv = new Interview();
        iv.uuid = UUID.randomUUID().toString();
        iv.applicationUuid = UUID.randomUUID().toString();
        iv.roundNumber = 1;
        iv.roundType = InterviewRoundType.FIRST;
        iv.scheduledAt = LocalDateTime.now().plusDays(1);
        iv.durationMinutes = 60;
        iv.status = InterviewStatus.HELD;
        iv.persist();
        String beforeStatus = iv.status.name();

        AiArtifact a = new AiArtifact();
        a.uuid = UUID.randomUUID().toString();
        a.subjectKind = AiSubjectKind.INTERVIEW;
        a.subjectUuid = iv.uuid;
        a.kind = AiArtifactKind.SCORECARD_ROUNDUP.name();
        a.promptVersion = "scorecard-roundup-v1";
        a.model = "gpt-5-nano";
        a.inputDigest = "z".repeat(64);
        a.state = "REVIEWED";
        a.persist();

        handler.apply(a, /*overrideJson*/ null);

        Interview reloaded = Interview.findById(iv.uuid);
        assertEquals(beforeStatus, reloaded.status.name(), "advisory handler must not mutate state");
        assertNull(reloaded.interviewKitArtifactUuid,
            "round-up handler must not touch the interview-kit pointer");
        assertNull(reloaded.roundUpSummary,
            "round-up summary must remain a deliberate human action");
    }
}
