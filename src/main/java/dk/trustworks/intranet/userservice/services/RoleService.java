package dk.trustworks.intranet.userservice.services;

import dk.trustworks.intranet.domain.user.entity.Role;
import dk.trustworks.intranet.security.AuthzStore;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.transaction.Transactional;
import jakarta.validation.Valid;
import jakarta.ws.rs.PathParam;
import java.util.List;
import java.util.UUID;

@ApplicationScoped
public class RoleService {

    @Inject
    AuthzStore authzStore;

    public List<Role> listAll(@PathParam("useruuid") String useruuid) {
        return Role.findByUseruuid(useruuid);
    }

    // Role assignments are authorization writes: each bumps authz_version in the
    // same transaction (Phase 4, 4.8) so every task's permission cache flushes
    // within ~1 s of the change.

    @Transactional
    public void create(String useruuid, @Valid Role role) {
        role.setUuid(UUID.randomUUID().toString());
        role.setUseruuid(useruuid);
        Role.persist(role);
        authzStore.bumpVersion();
    }

    @Transactional
    public void delete(String useruuid) {
        Role.delete("useruuid", useruuid);
        authzStore.bumpVersion();
    }
}