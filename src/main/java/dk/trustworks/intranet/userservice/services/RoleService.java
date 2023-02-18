package dk.trustworks.intranet.userservice.services;

import dk.trustworks.intranet.userservice.model.Role;

import javax.enterprise.context.ApplicationScoped;
import javax.transaction.Transactional;
import javax.validation.Valid;
import javax.ws.rs.PathParam;
import java.util.List;
import java.util.UUID;

@ApplicationScoped
public class RoleService {

    public List<Role> listAll(@PathParam("useruuid") String useruuid) {
        return Role.findByUseruuid(useruuid);
    }

    @Transactional
    public void create(String useruuid, @Valid Role role) {
        role.setUuid(UUID.randomUUID().toString());
        role.setUseruuid(useruuid);
        Role.persist(role);
    }

    @Transactional
    public void delete(String useruuid) {
        Role.delete("useruuid", useruuid);
    }
}