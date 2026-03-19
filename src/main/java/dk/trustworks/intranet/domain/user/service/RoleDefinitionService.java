package dk.trustworks.intranet.domain.user.service;

import dk.trustworks.intranet.domain.user.dto.CreateRoleDefinitionRequest;
import dk.trustworks.intranet.domain.user.dto.RoleDefinitionDTO;
import dk.trustworks.intranet.domain.user.dto.UpdateRoleDefinitionRequest;
import dk.trustworks.intranet.domain.user.entity.RoleDefinition;
import dk.trustworks.intranet.domain.user.entity.RoleDefinition.RoleInUseException;
import dk.trustworks.intranet.domain.user.entity.RoleDefinition.SystemRoleModificationException;

import jakarta.enterprise.context.ApplicationScoped;
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

    // --- Domain exception ---

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
