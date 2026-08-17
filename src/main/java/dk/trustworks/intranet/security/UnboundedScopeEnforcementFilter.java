package dk.trustworks.intranet.security;

import jakarta.annotation.Priority;
import jakarta.annotation.security.RolesAllowed;
import jakarta.inject.Inject;
import jakarta.ws.rs.ForbiddenException;
import jakarta.ws.rs.Priorities;
import jakarta.ws.rs.container.ContainerRequestContext;
import jakarta.ws.rs.container.ContainerRequestFilter;
import jakarta.ws.rs.container.ResourceInfo;
import jakarta.ws.rs.core.Context;
import jakarta.ws.rs.ext.Provider;
import lombok.extern.jbosslog.JBossLog;

import java.lang.reflect.Method;
import java.time.LocalDate;
import java.util.Set;

/**
 * Enforces {@link ScopeEnforced} (Phase 9, task 9.4): on an annotated surface,
 * an acting human whose grant of the endpoint's gating permission is anything
 * other than {@code ALL} is refused outright.
 *
 * <p>The gating permission is read from the matched method's — else the resource
 * class's — existing {@code @RolesAllowed} value; this filter never invents
 * policy. Multiple values keep their any-of semantics: one unbounded grant among
 * them admits. Non-scope-shaped values (no {@code :}) are ignored.
 *
 * <p>Decision table, in evaluation order:
 * <ol>
 *   <li>No {@code X-Requested-By} <em>human</em> actor — header absent or a
 *       machine identity per {@link HumanActor} → pass (Phase 12).</li>
 *   <li>Any gating permission granted at {@code ALL} → pass.</li>
 *   <li>Otherwise → 403. This covers both the bounded grant (a console scope
 *       edit on a surface with no subject dimension) and the no-grant case,
 *       and it fails closed on resolution errors because
 *       {@link AuthorizationService#resolveReach} already resolves those to
 *       {@link ScopeResolution#none()}.</li>
 * </ol>
 *
 * <p>The actor is read from the <em>raw request header</em>, never from
 * {@link RequestHeaderHolder}. Two independent reasons, both found live on
 * staging at the 9.1 deploy (findings, 2026-08-17): the holder is populated by
 * {@link HeaderInterceptor}, which runs at the JAX-RS default priority
 * ({@code Priorities.USER}, 5000) — <em>after</em> this filter at
 * {@code AUTHORIZATION + 100} — so reading the holder here always saw an empty
 * bean and passed everyone; and once populated the holder never holds
 * null/blank for machine callers — {@code HeaderInterceptor} back-fills the
 * JWT client id or {@code "anonymous"} — so holder-based detection after the
 * fact would flip the deliberate machine fail-open into a blanket deny.
 *
 * <p>Phase 12 replaces this with the general annotation-derived interceptor
 * (dial + shadow logging); until then the annotation is placed only on finance
 * surfaces whose every legitimate caller holds the gating permission at ALL —
 * proven by the Phase 9 golden diffs.
 */
@Provider
@ScopeEnforced
@Priority(Priorities.AUTHORIZATION + 100)
@JBossLog
public class UnboundedScopeEnforcementFilter implements ContainerRequestFilter {

    @Context
    ResourceInfo resourceInfo;

    @Inject
    AuthorizationService authorizationService;

    @Override
    public void filter(ContainerRequestContext requestContext) {
        String actor = HumanActor.uuidOrNull(requestContext.getHeaderString("X-Requested-By"));
        if (actor == null) {
            return;
        }
        RolesAllowed gate = effectiveRolesAllowed();
        if (gate == null) {
            // An annotated surface without a gate would otherwise silently skip
            // enforcement — deny instead (plan P8) so the misconfiguration is loud.
            log.errorf("ScopeEnforced surface %s has no @RolesAllowed — denying (fail closed)",
                    describeSurface());
            throw new ForbiddenException("Access rules for this surface are misconfigured");
        }
        for (String permissionKey : gate.value()) {
            if (permissionKey == null || !permissionKey.contains(":")) {
                continue;
            }
            ScopeResolution reach = authorizationService.resolveReach(
                    actor, permissionKey, LocalDate.now(), Set.of());
            if (reach.unbounded()) {
                return;
            }
        }
        log.infof("Unbounded-scope enforcement denied actor %s on %s — no ALL-scope grant of %s",
                actor, describeSurface(), String.join("/", gate.value()));
        throw new ForbiddenException(
                "This view spans records beyond your access scope");
    }

    private RolesAllowed effectiveRolesAllowed() {
        Method method = resourceInfo.getResourceMethod();
        if (method != null && method.isAnnotationPresent(RolesAllowed.class)) {
            return method.getAnnotation(RolesAllowed.class);
        }
        Class<?> clazz = resourceInfo.getResourceClass();
        return clazz != null ? clazz.getAnnotation(RolesAllowed.class) : null;
    }

    private String describeSurface() {
        Class<?> clazz = resourceInfo.getResourceClass();
        Method method = resourceInfo.getResourceMethod();
        return (clazz != null ? clazz.getSimpleName() : "?")
                + "." + (method != null ? method.getName() : "?");
    }
}
