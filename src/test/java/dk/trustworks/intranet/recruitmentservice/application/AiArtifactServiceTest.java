package dk.trustworks.intranet.recruitmentservice.application;

import dk.trustworks.intranet.recruitmentservice.AiEnabledTestProfile;
import dk.trustworks.intranet.recruitmentservice.domain.entities.AiArtifact;
import dk.trustworks.intranet.recruitmentservice.domain.entities.OutboxEntry;
import dk.trustworks.intranet.recruitmentservice.domain.enums.AiArtifactKind;
import dk.trustworks.intranet.recruitmentservice.domain.enums.AiArtifactState;
import dk.trustworks.intranet.recruitmentservice.domain.enums.AiSubjectKind;
import dk.trustworks.intranet.recruitmentservice.domain.enums.OutboxStatus;
import io.quarkus.test.TestTransaction;
import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.junit.TestProfile;
import jakarta.inject.Inject;
import org.junit.jupiter.api.Test;

import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

@QuarkusTest
@TestProfile(AiEnabledTestProfile.class)
class AiArtifactServiceTest {

    @Inject AiArtifactService service;

    @Test
    @TestTransaction
    void requestArtifact_createsGeneratingRowAndOutboxEntry() {
        String subj = UUID.randomUUID().toString();
        Map<String, Object> inputs = Map.of("candidateUuid", subj, "cvText", "John Doe...");

        AiArtifact a = service.requestArtifact(
            AiSubjectKind.CANDIDATE, subj, AiArtifactKind.CV_EXTRACTION, inputs, "actor-uuid");

        assertEquals(AiArtifactState.GENERATING.name(), a.state);
        assertNotNull(a.inputDigest);
        assertEquals(64, a.inputDigest.length());
        assertEquals("cv-extraction-v1", a.promptVersion);

        long outboxCount = OutboxEntry.count("status = ?1 AND payload LIKE ?2",
            OutboxStatus.PENDING.name(), "%" + a.uuid + "%");
        assertEquals(1, outboxCount);
    }

    @Test
    @TestTransaction
    void requestArtifact_idempotentOnSameInputs_returnsExisting() {
        String subj = UUID.randomUUID().toString();
        Map<String, Object> inputs = Map.of("candidateUuid", subj, "cvText", "fixed text");

        AiArtifact first = service.requestArtifact(
            AiSubjectKind.CANDIDATE, subj, AiArtifactKind.CV_EXTRACTION, inputs, "actor");
        AiArtifact second = service.requestArtifact(
            AiSubjectKind.CANDIDATE, subj, AiArtifactKind.CV_EXTRACTION, inputs, "actor");

        assertEquals(first.uuid, second.uuid, "same inputs -> same artifact");
        assertEquals(1, OutboxEntry.count("payload LIKE ?1", "%" + first.uuid + "%"),
            "no duplicate outbox entry");
    }

    @Test
    @TestTransaction
    void accept_transitionsGeneratedToReviewed_andRunsApplyHandler() {
        AiArtifact a = service.requestArtifact(
            AiSubjectKind.CANDIDATE, UUID.randomUUID().toString(),
            AiArtifactKind.CV_EXTRACTION, Map.of("k", "v"), "actor");
        service.markGenerated(a.uuid, "{\"firstName\":\"Alice\"}", "[{\"src\":\"line 1\"}]", null);

        service.accept(a.uuid, "reviewer-uuid");

        AiArtifact reloaded = AiArtifact.findById(a.uuid);
        assertEquals(AiArtifactState.REVIEWED.name(), reloaded.state);
        assertNotNull(reloaded.reviewedByUuid);
        assertNotNull(reloaded.reviewedAt);
    }

    @Test
    @TestTransaction
    void regenerate_createsNewRowWithDifferentDigest() {
        String subj = UUID.randomUUID().toString();
        AiArtifact first = service.requestArtifact(
            AiSubjectKind.CANDIDATE, subj, AiArtifactKind.CV_EXTRACTION, Map.of("k", "v"), "actor");
        AiArtifact regenerated = service.regenerate(first.uuid, "actor", "had typo");

        assertNotEquals(first.uuid, regenerated.uuid);
        assertNotEquals(first.inputDigest, regenerated.inputDigest,
            "regenerate adds a regen-counter to inputs to bust the digest cache");
    }
}
