package dk.trustworks.intranet.userservice.services;

import dk.trustworks.intranet.userservice.model.TeamRole;

import javax.enterprise.context.ApplicationScoped;
import java.util.List;

@ApplicationScoped
public class TeamRoleService {

    public List<TeamRole> listAll(String useruuid) {
        return TeamRole.find("useruuid like ?1", useruuid).list();
    }

}