package dk.trustworks.intranet.security;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import lombok.extern.jbosslog.JBossLog;

import java.time.LocalDate;
import java.util.EnumSet;
import java.util.HashSet;
import java.util.Set;

/**
 * Runtime {@link AuthorizationService}: granted scopes come from the catalogue
 * (via {@link EffectivePermissionService}'s version-flushed cache, so console
 * edits apply within ~1 s), subject sets from {@link AccessScopeResolver}.
 *
 * <p>Fail-closed to the letter of the phase file: any resolver failure yields
 * {@link ScopeResolution#none()} for the whole resolution — a partial answer
 * could silently drop the narrow tiers and keep a wide one, so nothing is kept.
 */
@ApplicationScoped
@JBossLog
public class AuthorizationServiceImpl implements AuthorizationService {

    @Inject
    EffectivePermissionService effectivePermissionService;

    @Inject
    AccessScopeResolver accessScopeResolver;

    @Override
    public ScopeResolution resolveReach(String actorUuid, String permissionKey,
                                        LocalDate asOf, Set<DataScope> disabledScopes) {
        if (actorUuid == null || actorUuid.isBlank()
                || permissionKey == null || permissionKey.isBlank() || asOf == null) {
            return ScopeResolution.none();
        }
        Set<DataScope> granted;
        try {
            granted = new HashSet<>(effectivePermissionService.effectiveScopes(actorUuid)
                    .getOrDefault(permissionKey, Set.of()));
        } catch (Exception e) {
            log.errorf(e, "Scope lookup failed for actor %s / %s — denying", actorUuid, permissionKey);
            return ScopeResolution.none();
        }
        if (disabledScopes != null) {
            granted.removeAll(disabledScopes);
        }
        if (granted.isEmpty()) {
            return ScopeResolution.none();
        }
        if (granted.contains(DataScope.ALL)) {
            return ScopeResolution.unboundedAll();
        }

        DataScope widest = granted.stream().max(Enum::compareTo).orElse(null);
        Set<String> subjects = new HashSet<>();
        try {
            for (DataScope scope : EnumSet.copyOf(granted)) {
                subjects.addAll(switch (scope) {
                    case OWN -> accessScopeResolver.resolveOwn(actorUuid);
                    case TEAM -> accessScopeResolver.resolveTeam(actorUuid, asOf);
                    case PRACTICE -> accessScopeResolver.resolvePractice(actorUuid, asOf);
                    case COMPANY -> accessScopeResolver.resolveCompany(actorUuid, asOf);
                    case ALL -> Set.<String>of(); // unreachable — handled above
                });
            }
        } catch (Exception e) {
            log.errorf(e, "Subject resolution failed for actor %s / %s — denying", actorUuid, permissionKey);
            return ScopeResolution.none();
        }
        return ScopeResolution.bounded(widest, subjects);
    }

    @Override
    public AccessDecision decideSubjectAccess(String actorUuid, String permissionKey, String subjectUuid,
                                              LocalDate asOf, Set<DataScope> disabledScopes) {
        if (subjectUuid == null || subjectUuid.isBlank()) {
            return new AccessDecision(false, null, false, 0, "No subject given — denied");
        }
        if (isSelfAccess(actorUuid, subjectUuid, disabledScopes)) {
            return new AccessDecision(true, DataScope.OWN, false, 1,
                    "Subject is the actor — OWN access, which no missing grant can withhold");
        }
        ScopeResolution reach = resolveReach(actorUuid, permissionKey, asOf, disabledScopes);
        if (reach.unbounded()) {
            return new AccessDecision(true, DataScope.ALL, true, 0,
                    "Granted at scope ALL — unbounded reach");
        }
        if (reach.isNone()) {
            return new AccessDecision(false, null, false, 0,
                    "No applicable grant of " + permissionKey + " — denied");
        }
        boolean allowed = reach.permits(subjectUuid);
        String reason = "Granted at scope " + reach.widestScope()
                + " (" + reach.subjects().size() + " subjects as of " + asOf + ") — subject "
                + (allowed ? "is among them" : "is not among them");
        return new AccessDecision(allowed, reach.widestScope(), false, reach.subjects().size(), reason);
    }

    /**
     * Asking about yourself is intrinsic, not role-conferred. Decided <em>before</em>
     * {@link #resolveReach} so that a missing, mis-seeded or unresolvable OWN grant
     * can never lock a person out of their own record.
     *
     * <p>Why this is not belt-and-braces: the {@code USER} role is implicit in this
     * system — a plain employee has no {@code roles} row at all, so V470's
     * {@code USER → salaries:read @ OWN} grant has nothing to resolve from and
     * {@link #resolveReach} correctly returns {@link ScopeResolution#none()}. That is
     * what 403'd employees on their own payslip in production from 2026-08-09 onward,
     * through both doors that reach this method: {@code SalaryResource}'s row-level
     * check and the BFF's {@code POST /authz/check}. HR/ADMIN were unaffected because
     * they hold explicit role rows at scope {@code ALL}.
     *
     * <p>Widens nothing. It only ever fires when the subject <em>is</em> the actor, and
     * never when the caller excluded the OWN tier ({@code allowSelf: false} — the
     * mutation surfaces, where self-service stays read-only). Every foreign subject
     * still goes through the full grant resolution.
     */
    private static boolean isSelfAccess(String actorUuid, String subjectUuid, Set<DataScope> disabledScopes) {
        return actorUuid != null && !actorUuid.isBlank()
                && actorUuid.trim().equals(subjectUuid.trim())
                && (disabledScopes == null || !disabledScopes.contains(DataScope.OWN));
    }
}
