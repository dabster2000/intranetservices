package dk.trustworks.intranet.security;

import jakarta.ws.rs.NameBinding;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Marks a resource class or method as an <em>unbounded-only</em> surface for the
 * acting human (Phase 9, task 9.4): when the request carries an
 * {@code X-Requested-By} actor, that actor's grant of the endpoint's gating
 * permission (its {@code @RolesAllowed} value) must have {@code data_scope=ALL}.
 * A bounded grant — or no grant — is refused with 403 by
 * {@link UnboundedScopeEnforcementFilter}, never silently served a partial
 * result: the surfaces carrying this annotation return company-wide row sets or
 * aggregates for which no subject-set filtering exists ("a partial aggregate
 * presented as a total is worse than a 403" — phase file 9.4, owner decision
 * 2026-08-06).
 *
 * <p>Headerless callers (batch jobs, API clients) are untouched until Phase 12,
 * mirroring the Phase 8 salary enforcement's deliberate, findings-recorded
 * fail-open. Anonymous flows never reach these resources: no session, no actor
 * header, and no client credential carrying these scopes.
 *
 * <p>Endpoints whose rows have a per-person subject dimension (expense ledgers,
 * bonus basis) do <em>not</em> use this annotation — they bind the resolved
 * subject set into the query instead (the Phase 8 rule: WHERE clause, never a
 * post-filter).
 */
@NameBinding
@Retention(RetentionPolicy.RUNTIME)
@Target({ElementType.TYPE, ElementType.METHOD})
public @interface ScopeEnforced {
}
