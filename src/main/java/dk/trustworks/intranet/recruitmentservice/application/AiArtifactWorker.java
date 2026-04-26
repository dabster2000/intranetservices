package dk.trustworks.intranet.recruitmentservice.application;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import dk.trustworks.intranet.recruitmentservice.domain.entities.AiArtifact;
import dk.trustworks.intranet.recruitmentservice.domain.entities.OutboxEntry;
import dk.trustworks.intranet.recruitmentservice.domain.enums.OutboxKind;
import dk.trustworks.intranet.recruitmentservice.domain.enums.OutboxStatus;
import dk.trustworks.intranet.recruitmentservice.ports.OpenAIPort;
import io.quarkus.scheduler.Scheduled;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.transaction.Transactional;
import lombok.extern.jbosslog.JBossLog;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

/**
 * Scheduled drainer for the AI_GENERATE outbox.
 *
 * <p>Runs every 30s (with {@code SKIP} concurrency so a slow OpenAI round-trip
 * doesn't pile up overlapping invocations). On each tick it loads PENDING
 * outbox rows whose {@code nextAttemptAt} is due, marks them IN_FLIGHT, and
 * delegates to {@link OpenAIPort}. Outcomes:
 *
 * <ul>
 *     <li><b>Success</b>: delegate to {@link AiArtifactService#markGenerated}
 *         and mark the outbox row DONE.</li>
 *     <li><b>Refusal</b> ({@link OpenAIPort.OpenAIPortRefusalException}):
 *         terminal immediately — mark artifact + outbox FAILED, no retry.</li>
 *     <li><b>Transient failure</b> (any other exception): increment
 *         {@code attemptCount}, schedule the next attempt with exponential
 *         backoff (1m → 5m → 25m), or mark FAILED once {@code MAX_ATTEMPTS}
 *         is reached.</li>
 * </ul>
 *
 * <p>Idempotency: the worker re-drives the same {@code OpenAIPort.generate}
 * call on retry. Per spec §9.1, the {@link AiArtifact} row is unchanged
 * (same UUID, same input digest); only the outbox attempt counter advances.
 */
@JBossLog
@ApplicationScoped
public class AiArtifactWorker {

    private static final int MAX_ATTEMPTS = 3;
    private static final long[] BACKOFF_SECONDS = {60, 300, 1500}; // 1m, 5m, 25m

    @Inject AiArtifactService artifacts;
    @Inject OpenAIPort openAIPort;

    private final ObjectMapper json = new ObjectMapper();

    @Scheduled(every = "30s", concurrentExecution = Scheduled.ConcurrentExecution.SKIP)
    void drainScheduled() {
        try {
            int handled = drainOnce();
            if (handled > 0) log.infof("[AiArtifactWorker] drained %d entries", handled);
        } catch (Exception e) {
            log.error("[AiArtifactWorker] scheduled drain failed", e);
        }
    }

    @Transactional
    public int drainOnce() {
        List<OutboxEntry> due = OutboxEntry.list(
            "status = ?1 AND kind = ?2 AND nextAttemptAt <= ?3",
            OutboxStatus.PENDING.name(), OutboxKind.AI_GENERATE.name(), LocalDateTime.now());
        int handled = 0;
        for (OutboxEntry e : due) {
            handleOne(e);
            handled++;
        }
        return handled;
    }

    private void handleOne(OutboxEntry e) {
        e.status = OutboxStatus.IN_FLIGHT.name();
        e.persistAndFlush();

        try {
            Map<String, Object> payload = json.readValue(e.payload, new TypeReference<>() {});
            String artifactUuid = (String) payload.get("artifactUuid");
            @SuppressWarnings("unchecked")
            Map<String, Object> inputs = (Map<String, Object>) payload.get("inputs");

            AiArtifact a = AiArtifact.findById(artifactUuid);
            if (a == null) {
                e.status = OutboxStatus.FAILED.name();
                e.lastError = "artifact " + artifactUuid + " not found";
                return;
            }

            OpenAIPort.GenerateResult r = openAIPort.generate(
                a.kind.toLowerCase().replace('_', '-'), a.promptVersion, inputs, /*outputSchema*/null);

            artifacts.markGenerated(a.uuid, r.outputJson(), r.evidenceJson(), null);
            e.status = OutboxStatus.DONE.name();
            e.lastError = null;
        } catch (OpenAIPort.OpenAIPortRefusalException refused) {
            // No retry on refusal — mark artifact + outbox FAILED immediately.
            String artifactUuid = e.targetRef;
            artifacts.markFailed(artifactUuid, "refused: " + refused.getMessage());
            e.status = OutboxStatus.FAILED.name();
            e.lastError = "refused: " + refused.getMessage();
        } catch (Exception ex) {
            // Transient — schedule next retry or terminal-fail.
            e.attemptCount += 1;
            e.lastError = ex.getMessage();
            if (e.attemptCount >= MAX_ATTEMPTS) {
                e.status = OutboxStatus.FAILED.name();
                artifacts.markFailed(e.targetRef, "max retries exceeded: " + ex.getMessage());
            } else {
                e.status = OutboxStatus.PENDING.name();
                e.nextAttemptAt = LocalDateTime.now()
                    .plusSeconds(BACKOFF_SECONDS[Math.min(e.attemptCount, BACKOFF_SECONDS.length - 1)]);
            }
            log.warnf("[AiArtifactWorker] outbox %s attempt %d failed: %s",
                e.uuid, e.attemptCount, ex.getMessage());
        }
    }
}
