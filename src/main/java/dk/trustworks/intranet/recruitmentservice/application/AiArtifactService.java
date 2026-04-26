package dk.trustworks.intranet.recruitmentservice.application;

import com.fasterxml.jackson.databind.ObjectMapper;
import dk.trustworks.intranet.recruitmentservice.application.handlers.AiArtifactApplyHandler;
import dk.trustworks.intranet.recruitmentservice.config.RecruitmentConfig;
import dk.trustworks.intranet.recruitmentservice.domain.ai.InputDigestCalculator;
import dk.trustworks.intranet.recruitmentservice.domain.entities.AiArtifact;
import dk.trustworks.intranet.recruitmentservice.domain.entities.OutboxEntry;
import dk.trustworks.intranet.recruitmentservice.domain.enums.AiArtifactKind;
import dk.trustworks.intranet.recruitmentservice.domain.enums.AiArtifactState;
import dk.trustworks.intranet.recruitmentservice.domain.enums.AiSubjectKind;
import dk.trustworks.intranet.recruitmentservice.domain.enums.OutboxKind;
import dk.trustworks.intranet.recruitmentservice.domain.enums.OutboxStatus;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.inject.Instance;
import jakarta.inject.Inject;
import jakarta.transaction.Transactional;
import jakarta.ws.rs.NotFoundException;
import jakarta.ws.rs.WebApplicationException;
import jakarta.ws.rs.core.Response;
import org.eclipse.microprofile.config.inject.ConfigProperty;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

/**
 * Aggregate-root service for {@link AiArtifact}.
 *
 * <p>Responsibilities:
 * <ul>
 *     <li>Idempotent request: same (subject_kind, subject_uuid, kind, input_digest)
 *         tuple returns the existing row instead of creating a duplicate.</li>
 *     <li>State machine transitions: GENERATING -> GENERATED/FAILED -> REVIEWED/OVERRIDDEN.</li>
 *     <li>Outbox enqueue for the AI worker (kind = AI_GENERATE).</li>
 *     <li>Apply-handler dispatch on accept/edit so reviewed artifacts can patch their
 *         subject aggregate (e.g. Candidate). Slice 2 ships the contract; Phase E
 *         (Slice 3) will plug in concrete handlers.</li>
 * </ul>
 */
@ApplicationScoped
public class AiArtifactService {

    @Inject InputDigestCalculator digester;
    @Inject RecruitmentConfig config;
    @Inject Instance<AiArtifactApplyHandler> handlers;

    @ConfigProperty(name = "openai.model", defaultValue = "gpt-5-nano")
    String model;

    private final ObjectMapper json = new ObjectMapper();

    @Transactional
    public AiArtifact requestArtifact(AiSubjectKind subjectKind, String subjectUuid,
                                      AiArtifactKind kind, Map<String, Object> inputs,
                                      String actorUuid) {
        ensureKindEnabled(kind);

        String digest = digester.digest(inputs);
        AiArtifact existing = AiArtifact.find(
            "subjectKind = ?1 AND subjectUuid = ?2 AND kind = ?3 AND inputDigest = ?4",
            subjectKind, subjectUuid, kind.name(), digest).firstResult();
        if (existing != null) return existing;

        AiArtifact a = new AiArtifact();
        a.uuid = UUID.randomUUID().toString();
        a.subjectKind = subjectKind;
        a.subjectUuid = subjectUuid;
        a.kind = kind.name();
        a.promptVersion = promptVersionFor(kind);
        a.model = model;
        a.inputDigest = digest;
        a.state = AiArtifactState.GENERATING.name();
        a.persist();

        enqueueOutbox(a.uuid, inputs);
        return a;
    }

    @Transactional
    public void markGenerated(String artifactUuid, String outputJson, String evidenceJson,
                              BigDecimal confidence) {
        AiArtifact a = AiArtifact.findById(artifactUuid);
        if (a == null) throw new NotFoundException();
        a.output = outputJson;
        a.evidence = evidenceJson;
        a.confidence = confidence;
        a.state = AiArtifactState.GENERATED.name();
        a.generatedAt = LocalDateTime.now();
    }

    @Transactional
    public void markFailed(String artifactUuid, String error) {
        AiArtifact a = AiArtifact.findById(artifactUuid);
        if (a == null) throw new NotFoundException();
        a.state = AiArtifactState.FAILED.name();
        try {
            a.output = json.writeValueAsString(Map.of("error", error == null ? "" : error));
        } catch (com.fasterxml.jackson.core.JsonProcessingException e) {
            throw new IllegalStateException("failed to serialise markFailed error JSON", e);
        }
    }

    @Transactional
    public void accept(String artifactUuid, String reviewerUuid) {
        AiArtifact a = requireReviewable(artifactUuid);
        a.state = AiArtifactState.REVIEWED.name();
        a.reviewedByUuid = reviewerUuid;
        a.reviewedAt = LocalDateTime.now();
        runApplyHandler(a, /*overrideJson=*/null);
    }

    @Transactional
    public void editAndOverride(String artifactUuid, String reviewerUuid, String overrideJson) {
        AiArtifact a = requireReviewable(artifactUuid);
        a.state = AiArtifactState.OVERRIDDEN.name();
        a.reviewedByUuid = reviewerUuid;
        a.reviewedAt = LocalDateTime.now();
        a.overrideJson = overrideJson;
        runApplyHandler(a, overrideJson);
    }

    @Transactional
    public void discard(String artifactUuid, String reviewerUuid) {
        AiArtifact a = requireReviewable(artifactUuid);
        a.state = AiArtifactState.OVERRIDDEN.name();  // record discard as override-with-empty
        a.reviewedByUuid = reviewerUuid;
        a.reviewedAt = LocalDateTime.now();
        a.overrideJson = "{\"discarded\":true}";
    }

    @Transactional
    public AiArtifact regenerate(String artifactUuid, String actorUuid, String reason) {
        AiArtifact prev = AiArtifact.findById(artifactUuid);
        if (prev == null) throw new NotFoundException();
        // Build new inputs with a regen counter to bust the digest cache.
        Map<String, Object> newInputs = new LinkedHashMap<>();
        newInputs.put("__regen_of", prev.uuid);
        newInputs.put("__regen_at", LocalDateTime.now().toString());
        newInputs.put("reason", reason == null ? "" : reason);
        return requestArtifact(prev.subjectKind, prev.subjectUuid,
            AiArtifactKind.valueOf(prev.kind), newInputs, actorUuid);
    }

    private AiArtifact requireReviewable(String uuid) {
        AiArtifact a = AiArtifact.findById(uuid);
        if (a == null) throw new NotFoundException();
        if (!AiArtifactState.GENERATED.name().equals(a.state)) {
            throw new WebApplicationException(
                "artifact not in GENERATED state (was " + a.state + ")",
                Response.Status.CONFLICT);
        }
        return a;
    }

    private void runApplyHandler(AiArtifact a, String overrideJson) {
        for (AiArtifactApplyHandler h : handlers) {
            if (h.handles(AiArtifactKind.valueOf(a.kind))) {
                h.apply(a, overrideJson);
                return;
            }
        }
        // No handler is fine — CANDIDATE_SUMMARY etc. are advisory only.
    }

    private void enqueueOutbox(String artifactUuid, Map<String, Object> inputs) {
        try {
            Map<String, Object> payload = new HashMap<>();
            payload.put("artifactUuid", artifactUuid);
            payload.put("inputs", inputs);
            OutboxEntry e = new OutboxEntry();
            e.uuid = UUID.randomUUID().toString();
            e.kind = OutboxKind.AI_GENERATE.name();
            e.payload = json.writeValueAsString(payload);
            e.targetRef = artifactUuid;
            e.status = OutboxStatus.PENDING.name();
            e.attemptCount = 0;
            e.nextAttemptAt = LocalDateTime.now();
            e.persist();
        } catch (Exception ex) {
            throw new IllegalStateException("failed to enqueue AI_GENERATE outbox", ex);
        }
    }

    private void ensureKindEnabled(AiArtifactKind kind) {
        if (!config.aiEnabled()) refuse(kind, "recruitment.ai.enabled=false");
        switch (kind) {
            case CV_EXTRACTION     -> { if (!config.aiCvExtractionEnabled())     refuse(kind, "cv-extraction disabled"); }
            case ROLE_BRIEF        -> { if (!config.aiRoleBriefEnabled())        refuse(kind, "role-brief disabled"); }
            case CANDIDATE_SUMMARY -> { if (!config.aiCandidateSummaryEnabled()) refuse(kind, "candidate-summary disabled"); }
            default                -> refuse(kind, "kind not implemented in slice 2");
        }
    }

    private void refuse(AiArtifactKind kind, String why) {
        throw new WebApplicationException("AI " + kind + " disabled: " + why,
            Response.Status.SERVICE_UNAVAILABLE);
    }

    private String promptVersionFor(AiArtifactKind kind) {
        return switch (kind) {
            case CV_EXTRACTION     -> "cv-extraction-v1";
            case ROLE_BRIEF        -> "role-brief-v1";
            case CANDIDATE_SUMMARY -> "candidate-summary-v1";
            default -> throw new IllegalArgumentException("unsupported in slice 2: " + kind);
        };
    }
}
