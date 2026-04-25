package dk.trustworks.intranet.recruitmentservice.application;

import dk.trustworks.intranet.domain.user.entity.Team;
import dk.trustworks.intranet.recruitmentservice.domain.entities.OpenRole;
import dk.trustworks.intranet.recruitmentservice.domain.entities.RoleAssignment;
import dk.trustworks.intranet.recruitmentservice.domain.enums.HiringCategory;
import dk.trustworks.intranet.security.ScopeContext;
import dk.trustworks.intranet.userservice.services.TeamService;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

import java.time.LocalDate;
import java.util.Set;
import java.util.function.Predicate;
import java.util.stream.Collectors;

@ApplicationScoped
public class RecruitmentRecordAccessService {

    private static final String ADMIN = "recruitment:admin";
    private static final String OFFER = "recruitment:offer";

    @Inject ScopeContext scope;
    @Inject TeamService teamService;

    public Predicate<OpenRole> openRolePredicate(String actorUuid) {
        if (scope.hasAnyScope(ADMIN, OFFER)) return role -> true;
        if (actorUuid == null) return role -> false;
        Set<String> ledTeams = ledTeamUuids(actorUuid);
        return role -> canSeeOpenRole(role, actorUuid, ledTeams);
    }

    public boolean canSeeOpenRole(OpenRole role, String actorUuid) {
        if (scope.hasAnyScope(ADMIN, OFFER)) return true;
        if (actorUuid == null) return false;
        return canSeeOpenRole(role, actorUuid, ledTeamUuids(actorUuid));
    }

    private boolean canSeeOpenRole(OpenRole role, String actorUuid, Set<String> ledTeams) {
        boolean assigned = RoleAssignment.count("roleUuid = ?1 and userUuid = ?2", role.uuid, actorUuid) > 0;
        if (role.hiringCategory == HiringCategory.PARTNER_OR_LEADERSHIP) {
            return assigned;
        }
        return assigned || ledTeams.contains(role.teamUuid);
    }

    private Set<String> ledTeamUuids(String actorUuid) {
        return teamService.findByRoles(actorUuid, LocalDate.now(), "LEADER").stream()
                .map(Team::getUuid)
                .collect(Collectors.toSet());
    }
}
