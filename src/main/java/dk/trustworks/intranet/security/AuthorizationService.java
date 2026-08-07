package dk.trustworks.intranet.security;

import java.time.LocalDate;
import java.util.Set;

/**
 * The single seam for authorization decisions (Phase 8, task 8.4). Resource
 * classes call this; they never implement permission or scope logic themselves.
 * This is what makes a policy engine adoptable later without touching the
 * resource classes.
 *
 * <p>Semantics:
 * <ul>
 *   <li>An actor's reach for a permission is the <em>union</em> across the
 *       scoped grants of every role they hold — a user with
 *       {@code USER → salaries:read @ OWN} and {@code TEAMLEAD → salaries:read
 *       @ TEAM} reaches themselves plus their led teams' members.</li>
 *   <li>{@code ALL} is unbounded; it is also the only scope that satisfies the
 *       legacy boolean surface ({@code GET /users/{uuid}/permissions}) — see
 *       {@link AuthzStore#loadEffectivePermissions}.</li>
 *   <li>Fail-closed (plan P8): unknown actor, no grant, and any resolution
 *       failure all deny; an error never widens reach.</li>
 * </ul>
 */
public interface AuthorizationService {

    /**
     * The actor's concrete reach for a permission: unbounded, a bounded subject
     * set, or nothing. {@code disabledScopes} lets a caller exclude tiers for
     * surfaces where they would be privilege escalation (the retired BFF guard's
     * {@code allowSelf: false} / {@code allowTeamLead: false} options).
     */
    ScopeResolution resolveReach(String actorUuid, String permissionKey,
                                 LocalDate asOf, Set<DataScope> disabledScopes);

    /** Whether the actor may touch one subject under the permission, with trace data. */
    AccessDecision decideSubjectAccess(String actorUuid, String permissionKey, String subjectUuid,
                                       LocalDate asOf, Set<DataScope> disabledScopes);

    /**
     * One decision, with what the access simulator needs for its trace:
     * "Anna holds salaries:read with scope TEAM (12 subjects as of 2026-08-03).
     * Bo is not among them."
     *
     * @param allowed      the verdict
     * @param widestScope  widest granted scope after exclusions; {@code null} = no grant
     * @param unbounded    {@code true} when an ALL-scope grant decided it
     * @param subjectCount resolved subject-set size (0 when unbounded or denied outright)
     * @param reason       human-readable deciding step, for traces and logs
     */
    record AccessDecision(boolean allowed, DataScope widestScope, boolean unbounded,
                          int subjectCount, String reason) {}
}
