package dk.trustworks.intranet.userservice.services;

import dk.trustworks.intranet.userservice.model.Team;
import dk.trustworks.intranet.userservice.model.TeamRole;
import dk.trustworks.intranet.userservice.model.User;
import lombok.extern.jbosslog.JBossLog;

import javax.enterprise.context.ApplicationScoped;
import javax.inject.Inject;
import javax.transaction.Transactional;
import javax.ws.rs.PathParam;
import javax.ws.rs.QueryParam;
import java.time.LocalDate;
import java.util.*;

@JBossLog
@ApplicationScoped
public class TeamService {

    @Inject
    UserService userService;

    public List<Team> listAll() {
        return Team.listAll();
    }

    public List<Team> findByRoles(String useruuid, LocalDate strDate, String... roles) {
        log.info("TeamService.findByRoles");
        log.info("useruuid = " + useruuid + ", strDate = " + strDate + ", roles = " + Arrays.deepToString(roles));
        List<Team> list = Team.find("select t from Team t " +
                "join TeamRole tu on tu.teamuuid = t.uuid " +
                "where membertype in ('" + String.join("','", roles) + "') " +
                "AND useruuid like ?1 " +
                "AND startdate <= ?2 AND (enddate > ?2 OR enddate is null)", useruuid, strDate).list();
        log.info("list = " + list.size());
        return list;
    }

    public List<User> getUsersByTeam(String teamuuid) {
        return User.find("select u from User u " +
                "join TeamRole tu on u.uuid = tu.useruuid " +
                "where membertype like 'MEMBER' AND " +
                "tu.teamuuid like ?1", teamuuid).list();
    }

    public List<User> getUsersByTeam(String teamuuid, LocalDate month) {
        List<User> users = User.find("select u from User u " +
                "join TeamRole tu on u.uuid = tu.useruuid " +
                "where membertype like 'MEMBER' AND " +
                "tu.teamuuid like ?1 AND " +
                "tu.startdate <= ?2 AND (tu.enddate > ?2 OR tu.enddate is null)", teamuuid, month).list();
        return userService.filterForActiveTeamMembers(month, users);
    }

    public List<User> getTeamLeadersByTeam(String teamuuid, LocalDate month) {
        List<User> users = User.find("select u from User u " +
                "join TeamRole tu on u.uuid = tu.useruuid " +
                "where membertype like 'LEADER' AND " +
                "tu.teamuuid like ?1 AND " +
                "tu.startdate <= ?2 AND (tu.enddate > ?2 OR tu.enddate is null)", teamuuid, month).list();
        return userService.filterForActiveTeamMembers(month, users);
    }

    public List<User> getTeammembersByTeamleadBonusEnabled() {
        return User.find("select u from User u " +
                "join TeamRole tu on u.uuid = tu.useruuid " +
                "join Team t on t.uuid = tu.teamuuid " +
                "where membertype like 'MEMBER' AND " +
                "t.teamleadbonus is true").list();
    }

    public List<User> getTeammembersByTeamleadBonusEnabledByMonth(LocalDate month) {
        List<User> users = User.find("select u from User u " +
                "join TeamRole tu on u.uuid = tu.useruuid " +
                "join Team t on t.uuid = tu.teamuuid " +
                "where membertype like 'MEMBER' AND " +
                "t.teamleadbonus is true AND " +
                "startdate <= ?1 AND " +
                "(enddate > ?1 OR enddate is null)", month).list();
        return userService.filterForActiveTeamMembers(month, users);
    }

    public List<User> getTeammembersByTeamleadBonusDisabled() {
        return User.find("select u from User u " +
                "join Teamrole tu on u.uuid = tu.useruuid " +
                "join Team t on t.uuid = tu.teamuuid " +
                "where membertype like 'MEMBER' AND " +
                "t.teamleadbonus is false AND " +
                "uuid not in ('f7602dd6-9daa-43cb-8712-e9b1b99dc3a9', 'f6e80289-2604-4a16-bcff-ee72affa3745')").list();
    }

    public List<User> getTeammembersByTeamleadBonusDisabledByMonth(LocalDate month) {
        List<User> users = User.find("select u from User u " +
                "join TeamRole tu on u.uuid = tu.useruuid " +
                "join Team t on t.uuid = tu.teamuuid " +
                "where membertype like 'MEMBER' AND " +
                "t.teamleadbonus is false AND " +
                "uuid not in ('f7602dd6-9daa-43cb-8712-e9b1b99dc3a9', 'f6e80289-2604-4a16-bcff-ee72affa3745')" +
                "startdate <= ?1 AND " +
                "(enddate > ?1 OR enddate is null)", month).list();
        return userService.filterForActiveTeamMembers(month, users);
    }

    public List<User> getOwners() {
        return User.find("select u from User u " +
                "join TeamRole tu on u.uuid = tu.useruuid " +
                "where membertype like 'MEMBER' AND " +
                "tu.teamuuid like ?1", "f7602dd6-9daa-43cb-8712-e9b1b99dc3a9").list();
    }

    public List<User> getOwnersByMonth(LocalDate month) {
        List<User> users = User.find("select u from User u " +
                "join TeamRole tu on u.uuid = tu.useruuid " +
                "where membertype like 'MEMBER' AND " +
                "tu.teamuuid like ?1 AND " +
                "startdate <= ?2 AND (enddate > ?2 OR enddate is null)", "f7602dd6-9daa-43cb-8712-e9b1b99dc3a9", month).list();
        return userService.filterForActiveTeamMembers(month, users);
    }

    public List<User> getTeamOps() {
        return User.find("select u from User u " +
                "join TeamRole tu on u.uuid = tu.useruuid " +
                "where membertype like 'MEMBER' AND " +
                "tu.teamuuid like ?1", "f6e80289-2604-4a16-bcff-ee72affa3745").list();
    }

    public List<User> getTeamOpsByMonth(LocalDate month) {
        List<User> users = User.find("select u from User u " +
                "join TeamRole tu on u.uuid = tu.useruuid " +
                "where membertype like 'MEMBER' AND " +
                "tu.teamuuid like ?1 AND " +
                "startdate <= ?2 AND (enddate > ?2 OR enddate is null)", "f6e80289-2604-4a16-bcff-ee72affa3745", month).list();
        return userService.filterForActiveTeamMembers(month, users);
    }

    public List<User> getUsersByTeamAndFiscalYear(@PathParam("teamuuid") String teamuuid, @QueryParam("fiscalyear") int intFiscalYear) {
        log.info("TeamResource.getUsersByTeamAndFiscalYear");
        log.info("teamuuid = " + teamuuid + ", intFiscalYear = " + intFiscalYear);
        LocalDate fiscalYear = LocalDate.of(intFiscalYear, 7,1);

        Map<String, User> users = new HashMap<>();
        for (int i = 0; i < 11; i++) {
            LocalDate date = fiscalYear.plusMonths(i);
            getUsersByTeam(teamuuid, date).forEach(user -> users.put(user.getUuid(), user));
        }
        return new ArrayList<>(users.values());
    }

    public List<TeamRole> getTeamRolesByUser(String useruuid) {
        return TeamRole.find("useruuid like ?1", useruuid).list();
    }

    @Transactional
    public void addTeamroleToUser(String teamuuid, TeamRole teamrole) {
        if(teamrole.getUuid()!=null && TeamRole.findByIdOptional(teamrole.getUuid()).isPresent()) TeamRole.deleteById(teamrole.getUuid());
        TeamRole.persist(new TeamRole(UUID.randomUUID().toString(), teamuuid, teamrole.getUseruuid(), teamrole.getStartdate(), teamrole.getEnddate(), teamrole.getTeammembertype()));
    }

    @Transactional
    public void removeUserFromTeam(String teamroleuuid) {
        TeamRole.deleteById(teamroleuuid);
    }
}
