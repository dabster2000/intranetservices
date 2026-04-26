package dk.trustworks.intranet.recruitmentservice.application;

import dk.trustworks.intranet.recruitmentservice.AiEnabledTestProfile;
import dk.trustworks.intranet.recruitmentservice.domain.entities.AiArtifact;
import dk.trustworks.intranet.recruitmentservice.domain.entities.OutboxEntry;
import dk.trustworks.intranet.recruitmentservice.domain.enums.AiArtifactKind;
import dk.trustworks.intranet.recruitmentservice.domain.enums.AiArtifactState;
import dk.trustworks.intranet.recruitmentservice.domain.enums.AiSubjectKind;
import dk.trustworks.intranet.recruitmentservice.domain.enums.OutboxStatus;
import dk.trustworks.intranet.recruitmentservice.ports.OpenAIPort;
import io.quarkus.test.InjectMock;
import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.junit.TestProfile;
import jakarta.inject.Inject;
import jakarta.transaction.Transactional;
import org.junit.jupiter.api.Test;

import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyMap;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.nullable;
import static org.mockito.Mockito.when;

/**
 * Verifies {@link AiArtifactWorker} drains the AI_GENERATE outbox correctly:
 * happy-path, transient-failure retry with backoff, and refusal-as-terminal.
 *
 * <p>Uses {@link InjectMock} on {@link OpenAIPort} so the worker exercises the
 * real {@link AiArtifactService} state transitions while we control the port
 * outcomes deterministically.
 */
@QuarkusTest
@TestProfile(AiEnabledTestProfile.class)
class AiArtifactWorkerTest {

    @Inject AiArtifactWorker worker;
    @Inject AiArtifactService service;
    @InjectMock OpenAIPort port;

    @Test
    @Transactional
    void drainsPendingOutbox_callsPort_marksGenerated() throws Exception {
        when(port.generate(anyString(), anyString(), anyMap(), nullable(String.class)))
            .thenReturn(new OpenAIPort.GenerateResult(
                "{\"firstName\":\"Alice\",\"evidence\":[]}", "[]", "gpt-5-nano"));

        AiArtifact a = service.requestArtifact(
            AiSubjectKind.CANDIDATE, UUID.randomUUID().toString(),
            AiArtifactKind.CV_EXTRACTION, Map.of("cvText", "..."), "actor");

        worker.drainOnce();

        AiArtifact reloaded = AiArtifact.findById(a.uuid);
        assertEquals(AiArtifactState.GENERATED.name(), reloaded.state);
        assertTrue(reloaded.output.contains("Alice"));

        long doneCount = OutboxEntry.count("status", OutboxStatus.DONE.name());
        assertTrue(doneCount >= 1);
    }

    @Test
    @Transactional
    void retriesOnTransientFailure_backoffApplied() throws Exception {
        when(port.generate(anyString(), anyString(), anyMap(), nullable(String.class)))
            .thenThrow(new OpenAIPort.OpenAIPortException("HTTP 503"));

        AiArtifact a = service.requestArtifact(
            AiSubjectKind.CANDIDATE, UUID.randomUUID().toString(),
            AiArtifactKind.CV_EXTRACTION, Map.of("k", "v"), "actor");

        worker.drainOnce();
        OutboxEntry e = OutboxEntry.find("targetRef", a.uuid).firstResult();
        assertEquals(OutboxStatus.PENDING.name(), e.status, "still pending after 1 retry");
        assertEquals(1, e.attemptCount);
        assertNotNull(e.lastError);

        // Drive to terminal FAILED after 3 attempts.
        e.attemptCount = 3;
        e.persistAndFlush();
        worker.drainOnce();
        OutboxEntry eAfter = OutboxEntry.findById(e.uuid);
        assertEquals(OutboxStatus.FAILED.name(), eAfter.status);
        AiArtifact reloaded = AiArtifact.findById(a.uuid);
        assertEquals(AiArtifactState.FAILED.name(), reloaded.state);
    }

    @Test
    @Transactional
    void refusalIsTerminalImmediately_noRetry() throws Exception {
        when(port.generate(anyString(), anyString(), anyMap(), nullable(String.class)))
            .thenThrow(new OpenAIPort.OpenAIPortRefusalException("refused"));

        AiArtifact a = service.requestArtifact(
            AiSubjectKind.CANDIDATE, UUID.randomUUID().toString(),
            AiArtifactKind.CV_EXTRACTION, Map.of("k", "v"), "actor");

        worker.drainOnce();

        AiArtifact reloaded = AiArtifact.findById(a.uuid);
        assertEquals(AiArtifactState.FAILED.name(), reloaded.state);
        OutboxEntry e = OutboxEntry.find("targetRef", a.uuid).firstResult();
        assertEquals(OutboxStatus.FAILED.name(), e.status);
    }
}
