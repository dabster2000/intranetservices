package dk.trustworks.intranet.security;

import dk.trustworks.intranet.aggregates.users.services.UserService;
import dk.trustworks.intranet.domain.user.entity.User;
import dk.trustworks.intranet.model.Company;
import dk.trustworks.intranet.userservice.model.TeamRole;
import dk.trustworks.intranet.userservice.model.enums.TeamMemberType;
import dk.trustworks.intranet.userservice.services.TeamService;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import lombok.extern.jbosslog.JBossLog;

import java.time.LocalDate;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * Resolves one {@link DataScope} kind to the concrete subject UUIDs it reaches
 * for an actor (Phase 8, tasks 8.2 + 8.3). Scope kinds are code — each kind has
 * a resolver here — while which scope a role gets is data.
 *
 * <p><strong>The relationship is always resolved server-side from Trustworks
 * data using the authenticated actor.</strong> A client-supplied team, practice
 * or company is never an authorization input.
 *
 * <p><strong>Temporal correctness (8.3, AC-11):</strong> team reach is evaluated
 * against the canonical temporal predicate {@code startdate <= asOf AND
 * (enddate > asOf OR enddate IS NULL)} — the same rule every backend team query
 * uses. A lead who left a team stops reaching its members the day the role ends,
 * for data of ANY month (Phase 8 policy: {@code asOf} is the day of the check,
 * not the month of the data — owner decision 2026-08-06).
 *
 * <p><strong>Fail-closed:</strong> every resolver returns the empty set on any
 * lookup failure, never a wider set. Callers treat an exception the same way.
 */
@ApplicationScoped
@JBossLog
public class AccessScopeResolver {

    @Inject
    TeamService teamService;

    @Inject
    UserService userService;

    /** {@code OWN}: the actor and nothing else. */
    public Set<String> resolveOwn(String actorUuid) {
        return Set.of(actorUuid);
    }

    /**
     * {@code TEAM}: the actor plus the members (including preboarding — the same
     * resolution {@code /teams/{uuid}/users/search/findByMonthIncludingPreboarding}
     * serves) of every team where the actor holds an active LEADER or SPONSOR
     * role as of {@code asOf}. Includes the actor so a lead's own record is
     * reachable regardless of their MEMBER rows.
     */
    public Set<String> resolveTeam(String actorUuid, LocalDate asOf) {
        Set<String> subjects = new HashSet<>();
        subjects.add(actorUuid);
        for (TeamRole role : ledTeamRoles(actorUuid)) {
            if (!isActiveLeadAsOf(role, asOf)) {
                continue;
            }
            for (User member : teamService.getUsersByTeamIncludingPreboarding(role.getTeamuuid(), asOf)) {
                subjects.add(member.getUuid());
            }
        }
        return subjects;
    }

    /** {@code PRACTICE}: active (or preboarding) members of the actor's practice, plus the actor. */
    public Set<String> resolvePractice(String actorUuid, LocalDate asOf) {
        Set<String> subjects = new HashSet<>();
        subjects.add(actorUuid);
        User actor = userService.findById(actorUuid, true);
        String practiceUuid = actor == null ? null : actor.getPracticeUuid();
        if (practiceUuid == null || practiceUuid.isBlank()) {
            return subjects; // no practice — reach stays the actor alone
        }
        List<User> practiceUsers = User.list("practiceUuid = ?1", practiceUuid);
        for (User member : userService.filterForActiveAndPreboardingTeamMembers(asOf, practiceUsers)) {
            subjects.add(member.getUuid());
        }
        return subjects;
    }

    /** {@code COMPANY}: employees of the actor's company as of {@code asOf}, plus the actor. */
    public Set<String> resolveCompany(String actorUuid, LocalDate asOf) {
        Set<String> subjects = new HashSet<>();
        subjects.add(actorUuid);
        User actor = userService.findById(actorUuid, false);
        Company company = actor == null ? null : actor.getUserStatus(asOf).getCompany();
        if (company == null) {
            return subjects;
        }
        for (User employee : userService.listAllByCompany(company.getUuid(), true)) {
            subjects.add(employee.getUuid());
        }
        return subjects;
    }

    /** Seam for database-free tests; production reads the {@code teamroles} table. */
    List<TeamRole> ledTeamRoles(String actorUuid) {
        return TeamRole.list("useruuid = ?1 and teammembertype in ?2",
                actorUuid, List.of(TeamMemberType.LEADER, TeamMemberType.SPONSOR));
    }

    /**
     * The mover-case predicate (AC-11), pure so the phase's canonical quiet bug —
     * a lead who changed teams in March still reaching the former team in April —
     * is pinned by a unit test. Canonical temporal rule: active on {@code asOf}
     * iff {@code startdate <= asOf} and {@code enddate} is open or after
     * {@code asOf}. (The retired BFF guard skipped the startdate check and
     * treated the end date as inclusive; this is the stricter, standard rule —
     * recorded in findings.)
     */
    static boolean isActiveLeadAsOf(TeamRole role, LocalDate asOf) {
        if (role == null || asOf == null) {
            return false;
        }
        boolean leadType = role.getTeammembertype() == TeamMemberType.LEADER
                || role.getTeammembertype() == TeamMemberType.SPONSOR;
        return leadType
                && role.getStartdate() != null && !role.getStartdate().isAfter(asOf)
                && (role.getEnddate() == null || role.getEnddate().isAfter(asOf));
    }
}
