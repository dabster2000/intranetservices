package dk.trustworks.intranet.security;

import jakarta.enterprise.context.ContextNotActiveException;
import jakarta.enterprise.context.RequestScoped;
import jakarta.inject.Inject;
import jakarta.ws.rs.ForbiddenException;
import lombok.extern.jbosslog.JBossLog;

import java.time.LocalDate;
import java.util.Set;

/**
 * Request-scoped convenience over {@link AuthorizationService} for resources
 * whose rows have a per-person subject dimension (Phase 9, task 9.4). It owns
 * the actor plumbing the Phase 8 salary enforcement wrote inline, so every
 * finance resource makes the same three moves the same way:
 *
 * <ul>
 *   <li>{@link #reachOrNull(String)} — the acting human's reach, or
 *       {@code null} when the request carries no {@code X-Requested-By} actor
 *       (batch/API-client traffic keeps pre-Phase-9 behaviour until Phase 12 —
 *       the deliberate, findings-recorded fail-open).</li>
 *   <li>{@link #requireSubjectWhenActor(String, String, String)} — 403 unless
 *       the acting human (when present) may touch one subject.</li>
 *   <li>{@link #actorHasUnbounded(String)} — actor-based replacement for
 *       client-credential role probes ({@code identity.hasRole(...)}), which a
 *       BFF system token always passes regardless of the human behind it.</li>
 * </ul>
 *
 * Anonymous flows never construct these calls: they carry no session and no
 * actor header, and their credentials hold none of the finance scopes.
 */
@RequestScoped
@JBossLog
public class ScopeGuard {

    @Inject
    AuthorizationService authorizationService;

    @Inject
    RequestHeaderHolder requestHeaderHolder;

    /**
     * The acting human's uuid, or {@code null} for machine callers. By the time
     * resource code runs, {@link HeaderInterceptor} has populated the holder and
     * a headerless (machine) call carries the JWT client id or
     * {@code "anonymous"} — never null/blank — so the human/machine
     * discriminator is the {@link HumanActor} UUID shape, not presence
     * (findings, 2026-08-17).
     */
    public String actorOrNull() {
        try {
            return HumanActor.uuidOrNull(requestHeaderHolder.getUserUuid());
        } catch (ContextNotActiveException e) {
            return null;
        }
    }

    /**
     * The acting human's reach for a permission, or {@code null} when the caller
     * carries no actor header. A non-null result is fail-closed by construction
     * ({@link AuthorizationService#resolveReach} resolves every failure to
     * {@link ScopeResolution#none()}).
     */
    public ScopeResolution reachOrNull(String permissionKey) {
        String actor = actorOrNull();
        if (actor == null) {
            return null;
        }
        return authorizationService.resolveReach(actor, permissionKey, LocalDate.now(), Set.of());
    }

    /**
     * 403 unless the acting human (when present) may touch {@code subjectUuid}
     * under {@code permissionKey}. Headerless callers pass — Phase 12.
     */
    public void requireSubjectWhenActor(String permissionKey, String subjectUuid, String denialMessage) {
        String actor = actorOrNull();
        if (actor == null) {
            return;
        }
        AuthorizationService.AccessDecision decision = authorizationService.decideSubjectAccess(
                actor, permissionKey, subjectUuid, LocalDate.now(), Set.of());
        if (!decision.allowed()) {
            log.infof("%s scope denied — target %s outside actor %s's reach (%s)",
                    permissionKey, subjectUuid, actor, decision.reason());
            throw new ForbiddenException(denialMessage);
        }
    }

    /** Whether the acting human holds {@code permissionKey} at scope ALL. False without an actor. */
    public boolean actorHasUnbounded(String permissionKey) {
        ScopeResolution reach = reachOrNull(permissionKey);
        return reach != null && reach.unbounded();
    }
}
