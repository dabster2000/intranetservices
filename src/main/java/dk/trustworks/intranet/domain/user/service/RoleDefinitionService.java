package dk.trustworks.intranet.domain.user.service;

import dk.trustworks.intranet.domain.user.dto.CreateRoleDefinitionRequest;
import dk.trustworks.intranet.domain.user.dto.RoleDefinitionDTO;
import dk.trustworks.intranet.domain.user.dto.UpdateRoleDefinitionRequest;
import dk.trustworks.intranet.domain.user.entity.RoleDefinition;
import dk.trustworks.intranet.domain.user.entity.RoleDefinition.RoleInUseException;
import dk.trustworks.intranet.domain.user.entity.RoleDefinition.SystemRoleModificationException;

import dk.trustworks.intranet.security.AuthzStore;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.transaction.Transactional;
import jakarta.ws.rs.NotFoundException;
import java.util.List;
import java.util.Optional;

/**
 * Application service for RoleDefinition aggregate.
 * Orchestrates CRUD operations — business rules live in the entity.
 */
@ApplicationScoped
public class RoleDefinitionService {

    @Inject
    AuthzStore authzStore;

    public List<RoleDefinitionDTO> listAll() {
        return RoleDefinition.listAllOrdered().stream()
                .map(this::toDTO)
                .toList();
    }

    public Optional<RoleDefinitionDTO> findByName(String name) {
        return RoleDefinition.findByName(name)
                .map(this::toDTO);
    }

    @Transactional
    public RoleDefinitionDTO create(CreateRoleDefinitionRequest request) {
        if (RoleDefinition.findByName(request.name()).isPresent()) {
            throw new RoleAlreadyExistsException(request.name());
        }
        var roleDefinition = RoleDefinition.create(request.name(), request.displayLabel());
        roleDefinition.persist();
        return toDTO(roleDefinition);
    }

    @Transactional
    public RoleDefinitionDTO update(String name, UpdateRoleDefinitionRequest request) {
        var roleDefinition = RoleDefinition.findByName(name)
                .orElseThrow(() -> new NotFoundException("Role definition not found: " + name));

        if (roleDefinition.isSystem()) {
            throw new SystemRoleModificationException(name);
        }

        roleDefinition.updateDisplayLabel(request.displayLabel());
        roleDefinition.persist();
        return toDTO(roleDefinition);
    }

    @Transactional
    public void delete(String name) {
        var roleDefinition = RoleDefinition.findByName(name)
                .orElseThrow(() -> new NotFoundException("Role definition not found: " + name));

        roleDefinition.validateCanDelete();

        long usageCount = roleDefinition.countUsages();
        if (usageCount > 0) {
            throw new RoleInUseException(name, usageCount);
        }

        // Phase 4 (owner decision 2026-08-06): a role bound to catalogue permissions
        // is blocked from deletion with a clear message, not cascaded. The FK
        // (fk_role_permission_role, ON DELETE RESTRICT) would reject the delete
        // anyway; this check turns that DB error into an actionable 409.
        long bindingCount = authzStore.countPermissionBindings(name);
        if (bindingCount > 0) {
            throw new RoleHasPermissionBindingsException(name, bindingCount);
        }

        roleDefinition.delete();
    }

    private RoleDefinitionDTO toDTO(RoleDefinition rd) {
        return new RoleDefinitionDTO(
                rd.getName(),
                rd.getDisplayLabel(),
                rd.isSystem(),
                rd.countUsages(),
                rd.getCreatedAt(),
                rd.getUpdatedAt()
        );
    }

    // --- Domain exceptions ---

    /**
     * The role still grants permissions in the Phase 4 catalogue. Deleting it is
     * blocked (owner decision, 2026-08-06) until the bindings are deliberately
     * removed — Phase 7's admin console is the intended tool for that.
     */
    public static class RoleHasPermissionBindingsException extends RuntimeException {
        private final String roleName;
        private final long bindingCount;

        public RoleHasPermissionBindingsException(String roleName, long bindingCount) {
            super("Role '" + roleName + "' grants " + bindingCount + " permission(s) in the permission catalogue"
                    + " and cannot be deleted. Remove its permission bindings first.");
            this.roleName = roleName;
            this.bindingCount = bindingCount;
        }

        public String getRoleName() {
            return roleName;
        }

        public long getBindingCount() {
            return bindingCount;
        }
    }

    public static class RoleAlreadyExistsException extends RuntimeException {
        private final String roleName;

        public RoleAlreadyExistsException(String roleName) {
            super("Role definition '" + roleName + "' already exists");
            this.roleName = roleName;
        }

        public String getRoleName() {
            return roleName;
        }
    }
}
