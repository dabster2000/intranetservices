package dk.trustworks.intranet.userservice.services;

import dk.trustworks.intranet.userservice.model.TeamRole;

import jakarta.enterprise.context.ApplicationScoped;
import java.util.List;

@ApplicationScoped
public class TeamRoleService {

    public List<TeamRole> listAll(String useruuid) {
        return TeamRole.find("useruuid", useruuid).list();
    }

}