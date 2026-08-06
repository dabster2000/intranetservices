package dk.trustworks.intranet.security;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.context.ContextNotActiveException;
import jakarta.inject.Inject;
import jakarta.transaction.Transactional;
import lombok.extern.jbosslog.JBossLog;

import java.time.LocalDateTime;

/**
 * Records authorization mutations in {@code authz_audit} <strong>and</strong> bumps
 * {@code authz_version}, in the caller's transaction (Phase 7, task 7.2).
 *
 * <p>Both effects are {@link Transactional.TxType#MANDATORY}: calling this outside the
 * mutation's transaction is a programming error, and if either the audit insert or the
 * version bump fails, the mutation rolls back with them. The version bump is what makes
 * a change take effect within ~1 s across all tasks (Phase 4.8 cache); the audit row is
 * what makes it attributable. They must not be able to diverge.
 *
 * <p><strong>Actor limitation (recorded in findings):</strong> the actor is resolved via
 * {@link RequestHeaderHolder}, whose precedence is X-Requested-By → JWT
 * preferred_username → ?username= → "anonymous". Until Phase 11 the header is supplied
 * by the BFF and not cryptographically bound to the user, so the trail is attributable
 * but not yet non-repudiable.
 */
@ApplicationScoped
@JBossLog
public class AuthzAuditService {

    @Inject
    RequestHeaderHolder requestHeaderHolder;

    @Inject
    AuthzStore authzStore;

    @Inject
    ObjectMapper objectMapper;

    /**
     * Writes one audit row and bumps {@code authz_version}, both inside the caller's
     * transaction. {@code before} / {@code after} are serialized to JSON; {@code null}
     * means "did not exist" / "no longer grants".
     *
     * @throws IllegalStateException when serialization fails — deliberately unchecked
     *                               and uncaught so the surrounding mutation rolls back
     *                               rather than committing unaudited.
     */
    @Transactional(Transactional.TxType.MANDATORY)
    public void record(String action, String targetType, String targetId, Object before, Object after) {
        AuthzAudit row = new AuthzAudit();
        row.setActorUuid(resolveActor());
        row.setAction(action);
        row.setTargetType(targetType);
        row.setTargetId(targetId);
        row.setBeforeJson(toJson(before));
        row.setAfterJson(toJson(after));
        row.setAt(LocalDateTime.now());
        row.persist();
        authzStore.bumpVersion();
    }

    private String resolveActor() {
        try {
            String actor = requestHeaderHolder.getUserUuid();
            return (actor == null || actor.isEmpty()) ? "system" : actor;
        } catch (ContextNotActiveException e) {
            // Mutation initiated outside a request (scheduled job, batch). The write
            // itself must still be audited — attribute it to the system.
            return "system";
        }
    }

    private String toJson(Object value) {
        if (value == null) {
            return null;
        }
        try {
            return objectMapper.writeValueAsString(value);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("Failed to serialize authz audit payload", e);
        }
    }
}
