package dk.trustworks.intranet.aggregates.bonus.individual.services;

import dk.trustworks.intranet.aggregates.bonus.individual.dto.IndividualBonusRuleRequest;
import dk.trustworks.intranet.aggregates.bonus.individual.entity.IndividualBonusCreateIdempotency;
import dk.trustworks.intranet.aggregates.bonus.individual.entity.IndividualBonusPreviewProof;
import dk.trustworks.intranet.aggregates.bonus.individual.exceptions.IndividualBonusException;
import io.quarkus.hibernate.orm.panache.Panache;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.persistence.LockModeType;
import jakarta.transaction.Transactional;

import java.security.SecureRandom;
import java.security.MessageDigest;
import java.time.Clock;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.HexFormat;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

/** Database-backed, single-use Preview proofs and CREATE idempotency reservations. */
@ApplicationScoped
public class IndividualBonusPreviewProofService {

    public static final String PROOF_HEADER = "X-Individual-Bonus-Preview-Proof";
    public static final String PROOF_EXPIRES_HEADER = "X-Individual-Bonus-Preview-Proof-Expires-At";
    private static final SecureRandom RANDOM = new SecureRandom();
    private static final int TTL_MINUTES = 10;

    @Inject IndividualBonusCanonicalizer canonicalizer;

    public record IssuedProof(String token, LocalDateTime expiresAt, String payloadHash) {
        public String expiresAtUtc() { return expiresAt.toInstant(ZoneOffset.UTC).toString(); }
    }

    public record CompletedCreate(String resultRuleUuid) { }

    @Transactional
    public IssuedProof issueAdjustmentProof(String actorUuid, String userUuid, String ruleUuid,
                                            Long ruleRevision, String adjustmentUuid, Long targetVersion,
                                            Map<String, ?> payload) {
        String payloadHash = adjustmentPayloadHash(actorUuid, userUuid, ruleUuid, ruleRevision,
                adjustmentUuid, targetVersion, payload);
        byte[] bytes = new byte[32];
        RANDOM.nextBytes(bytes);
        String rawToken = Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
        LocalDateTime now = LocalDateTime.now(Clock.systemUTC());
        IndividualBonusPreviewProof proof = new IndividualBonusPreviewProof();
        proof.setTokenHash(canonicalizer.sha256(rawToken));
        proof.setPayloadHash(payloadHash);
        proof.setAction("ADJUSTMENT_CONFIRM");
        proof.setActorUuid(actorUuid);
        proof.setUserUuid(userUuid);
        proof.setRuleUuid(ruleUuid);
        proof.setRuleRevision(ruleRevision);
        proof.setTargetType("ADJUSTMENT");
        proof.setTargetUuid(adjustmentUuid);
        proof.setTargetVersion(targetVersion);
        proof.setIssuedAt(now);
        proof.setExpiresAt(now.plusMinutes(TTL_MINUTES));
        proof.persist();
        return new IssuedProof(rawToken, proof.getExpiresAt(), payloadHash);
    }

    @Transactional
    public void verifyAndConsumeAdjustmentProof(String rawToken, String actorUuid, String userUuid,
                                                String ruleUuid, Long ruleRevision, String adjustmentUuid,
                                                Long targetVersion, Map<String, ?> payload) {
        if (rawToken == null || rawToken.isBlank()) {
            throw new IndividualBonusException(428, "PREVIEW_PROOF_REQUIRED",
                    "Preview proof is required", "previewProof");
        }
        String payloadHash = adjustmentPayloadHash(actorUuid, userUuid, ruleUuid, ruleRevision,
                adjustmentUuid, targetVersion, payload);
        IndividualBonusPreviewProof proof = Panache.getEntityManager().find(
                IndividualBonusPreviewProof.class, canonicalizer.sha256(rawToken), LockModeType.PESSIMISTIC_WRITE);
        if (proof == null || !"ADJUSTMENT_CONFIRM".equals(proof.getAction())
                || proof.getConsumedAt() != null || proof.getExpiresAt().isBefore(LocalDateTime.now(Clock.systemUTC()))) {
            String code = proof != null && proof.getConsumedAt() != null
                    ? "PREVIEW_PROOF_REPLAYED" : "PREVIEW_PROOF_EXPIRED";
            throw new IndividualBonusException(409, code, "Preview is no longer valid", "previewProof");
        }
        if (!safeDigestEquals(proof.getPayloadHash(), payloadHash)
                || !Objects.equals(proof.getActorUuid(), actorUuid)
                || !Objects.equals(proof.getUserUuid(), userUuid)
                || !Objects.equals(proof.getRuleUuid(), ruleUuid)
                || !Objects.equals(proof.getRuleRevision(), ruleRevision)
                || !Objects.equals(proof.getTargetUuid(), adjustmentUuid)
                || !Objects.equals(proof.getTargetVersion(), targetVersion)) {
            throw new IndividualBonusException(409, "PREVIEW_PROOF_PAYLOAD_MISMATCH",
                    "Preview is no longer valid", "previewProof");
        }
        proof.setConsumedAt(LocalDateTime.now(Clock.systemUTC()));
    }

    @Transactional
    public IssuedProof issueRuleProof(String action, String actorUuid, String userUuid, String ruleUuid,
                                      Long ruleRevision, String idempotencyKey,
                                      IndividualBonusRuleRequest request, String proofAction) {
        validateAction(action);
        String payloadHash = rulePayloadHash(action, actorUuid, userUuid, ruleUuid, ruleRevision,
                idempotencyKey, request);
        if ("CREATE".equals(action)) reserveCreateKey(idempotencyKey, actorUuid, userUuid, payloadHash);

        byte[] bytes = new byte[32];
        RANDOM.nextBytes(bytes);
        String rawToken = Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
        LocalDateTime now = LocalDateTime.now(Clock.systemUTC());
        IndividualBonusPreviewProof proof = new IndividualBonusPreviewProof();
        proof.setTokenHash(canonicalizer.sha256(rawToken));
        proof.setPayloadHash(payloadHash);
        proof.setAction(proofAction == null ? action : proofAction);
        proof.setActorUuid(actorUuid);
        proof.setUserUuid(userUuid);
        proof.setRuleUuid(ruleUuid);
        proof.setRuleRevision(ruleRevision);
        proof.setTargetType("CREATE".equals(action) ? "RULE_CREATE" : "RULE_UPDATE");
        proof.setTargetUuid(ruleUuid);
        proof.setTargetVersion(ruleRevision);
        proof.setIdempotencyKey(idempotencyKey);
        proof.setIssuedAt(now);
        proof.setExpiresAt(now.plusMinutes(TTL_MINUTES));
        proof.persist();
        return new IssuedProof(rawToken, proof.getExpiresAt(), payloadHash);
    }

    @Transactional
    public Optional<CompletedCreate> verifyAndConsumeRuleProof(String rawToken, String action, String actorUuid,
                                                               String userUuid, String ruleUuid,
                                                               Long ruleRevision, String idempotencyKey,
                                                               IndividualBonusRuleRequest request) {
        if (rawToken == null || rawToken.isBlank()) {
            throw new IndividualBonusException(428, "PREVIEW_PROOF_REQUIRED",
                    "Preview proof is required", "previewProof");
        }
        String payloadHash = rulePayloadHash(action, actorUuid, userUuid, ruleUuid, ruleRevision,
                idempotencyKey, request);
        IndividualBonusPreviewProof proof = Panache.getEntityManager().find(
                IndividualBonusPreviewProof.class, canonicalizer.sha256(rawToken), LockModeType.PESSIMISTIC_WRITE);
        if (proof == null) {
            throw new IndividualBonusException(409, "PREVIEW_PROOF_PAYLOAD_MISMATCH",
                    "Preview is no longer valid", "previewProof");
        }

        if (proof.getConsumedAt() != null || proof.getExpiresAt().isBefore(LocalDateTime.now(Clock.systemUTC()))) {
            if ("CREATE".equals(action)) {
                Optional<CompletedCreate> completed = completedCreate(idempotencyKey, actorUuid, userUuid, payloadHash);
                if (completed.isPresent()) return completed;
            }
            String code = proof.getConsumedAt() != null ? "PREVIEW_PROOF_REPLAYED" : "PREVIEW_PROOF_EXPIRED";
            throw new IndividualBonusException(409, code, "Preview is no longer valid", "previewProof");
        }
        if (!safeDigestEquals(proof.getPayloadHash(), payloadHash)
                || !Objects.equals(proof.getActorUuid(), actorUuid)
                || !Objects.equals(proof.getUserUuid(), userUuid)
                || !Objects.equals(proof.getRuleUuid(), ruleUuid)
                || !Objects.equals(proof.getRuleRevision(), ruleRevision)
                || !Objects.equals(proof.getIdempotencyKey(), idempotencyKey)
                || !(Objects.equals(proof.getAction(), action)
                    || ("UPDATE".equals(action) && "FAIL_SAFE_REDUCTION".equals(proof.getAction())))) {
            throw new IndividualBonusException(409, "PREVIEW_PROOF_PAYLOAD_MISMATCH",
                    "Preview is no longer valid", "previewProof");
        }
        proof.setConsumedAt(LocalDateTime.now(Clock.systemUTC()));
        return Optional.empty();
    }

    @Transactional
    public void completeCreate(String idempotencyKey, String actorUuid, String userUuid,
                               String payloadHash, String resultRuleUuid) {
        IndividualBonusCreateIdempotency row = Panache.getEntityManager().find(
                IndividualBonusCreateIdempotency.class, idempotencyKey, LockModeType.PESSIMISTIC_WRITE);
        if (row == null || !matches(row, actorUuid, userUuid, payloadHash)) {
            throw new IndividualBonusException(409, "IDEMPOTENCY_KEY_REUSED",
                    "Idempotency key is already bound to another request", "idempotencyKey");
        }
        row.setState("COMPLETED");
        row.setResultRuleUuid(resultRuleUuid);
        row.setCompletedAt(LocalDateTime.now(Clock.systemUTC()));
    }

    public String rulePayloadHash(String action, String actorUuid, String userUuid, String ruleUuid,
                                  Long ruleRevision, String idempotencyKey, IndividualBonusRuleRequest request) {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("action", action);
        payload.put("actorUuid", actorUuid);
        payload.put("userUuid", userUuid);
        payload.put("ruleUuid", ruleUuid);
        payload.put("ruleRevision", ruleRevision);
        payload.put("idempotencyKey", idempotencyKey);
        Map<String, Object> normalizedRequest = new LinkedHashMap<>();
        normalizedRequest.put("userUuid", request.userUuid());
        normalizedRequest.put("name", request.name() == null ? null : request.name().trim());
        normalizedRequest.put("effectiveFrom", request.effectiveFrom());
        normalizedRequest.put("effectiveTo", request.effectiveTo());
        normalizedRequest.put("replaces", request.replaces());
        normalizedRequest.put("active", request.active() == null || request.active());
        normalizedRequest.put("spec", request.spec());
        normalizedRequest.put("revision", request.revision());
        normalizedRequest.put("ruleUuid", request.ruleUuid());
        payload.put("request", normalizedRequest);
        return canonicalizer.sha256(canonicalizer.canonicalizeMap(payload));
    }

    private String adjustmentPayloadHash(String actorUuid, String userUuid, String ruleUuid,
                                         Long ruleRevision, String adjustmentUuid, Long targetVersion,
                                         Map<String, ?> boundPayload) {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("action", "ADJUSTMENT_CONFIRM");
        payload.put("actorUuid", actorUuid);
        payload.put("userUuid", userUuid);
        payload.put("ruleUuid", ruleUuid);
        payload.put("ruleRevision", ruleRevision);
        payload.put("adjustmentUuid", adjustmentUuid);
        payload.put("targetVersion", targetVersion);
        payload.put("payload", boundPayload);
        return canonicalizer.sha256(canonicalizer.canonicalizeMap(payload));
    }

    private void reserveCreateKey(String rawKey, String actorUuid, String userUuid, String payloadHash) {
        UUID key = parseUuid(rawKey, "IDEMPOTENCY_KEY_REQUIRED", "Idempotency-Key must be a UUID");
        IndividualBonusCreateIdempotency row = Panache.getEntityManager().find(
                IndividualBonusCreateIdempotency.class, key.toString(), LockModeType.PESSIMISTIC_WRITE);
        if (row == null) {
            row = new IndividualBonusCreateIdempotency();
            row.setIdempotencyKey(key.toString());
            row.setActorUuid(actorUuid);
            row.setUserUuid(userUuid);
            row.setPayloadHash(payloadHash);
            row.setState("PREVIEWED");
            row.setCreatedAt(LocalDateTime.now(Clock.systemUTC()));
            row.persist();
            return;
        }
        if (!Objects.equals(row.getActorUuid(), actorUuid) || !Objects.equals(row.getUserUuid(), userUuid)
                || "COMPLETED".equals(row.getState())) {
            throw new IndividualBonusException(409, "IDEMPOTENCY_KEY_REUSED",
                    "Idempotency key is already bound to another request", "idempotencyKey");
        }
        row.setPayloadHash(payloadHash);
    }

    private Optional<CompletedCreate> completedCreate(String idempotencyKey, String actorUuid,
                                                       String userUuid, String payloadHash) {
        if (idempotencyKey == null) return Optional.empty();
        IndividualBonusCreateIdempotency row = IndividualBonusCreateIdempotency.findById(idempotencyKey);
        if (row != null && "COMPLETED".equals(row.getState()) && matches(row, actorUuid, userUuid, payloadHash)) {
            return Optional.of(new CompletedCreate(row.getResultRuleUuid()));
        }
        return Optional.empty();
    }

    private static boolean matches(IndividualBonusCreateIdempotency row, String actorUuid,
                                   String userUuid, String payloadHash) {
        return Objects.equals(row.getActorUuid(), actorUuid)
                && Objects.equals(row.getUserUuid(), userUuid)
                && safeDigestEquals(row.getPayloadHash(), payloadHash);
    }

    static boolean safeDigestEquals(String left, String right) {
        if (left == null || right == null || left.length() != 64 || right.length() != 64) return false;
        try {
            return MessageDigest.isEqual(HexFormat.of().parseHex(left), HexFormat.of().parseHex(right));
        } catch (IllegalArgumentException malformedHex) {
            return false;
        }
    }

    private static void validateAction(String action) {
        if (!"CREATE".equals(action) && !"UPDATE".equals(action)) {
            throw new IndividualBonusException(400, "INVALID_PREVIEW_ACTION",
                    "action must be CREATE or UPDATE", "action");
        }
    }

    private static UUID parseUuid(String value, String code, String message) {
        if (value == null || value.isBlank()) throw new IndividualBonusException(400, code, message, "idempotencyKey");
        try {
            return UUID.fromString(value);
        } catch (IllegalArgumentException e) {
            throw new IndividualBonusException(400, code, message, "idempotencyKey");
        }
    }
}
