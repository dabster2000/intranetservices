package dk.trustworks.intranet.domain.user.entity;

import io.quarkus.hibernate.orm.panache.PanacheEntityBase;

import jakarta.persistence.*;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

/**
 * Aggregate root for role definitions.
 * Maps to the role_definition table which serves as the source of truth
 * for all valid role names in the system.
 */
@Entity
@Table(name = "role_definition")
public class RoleDefinition extends PanacheEntityBase {

    @Id
    @Column(name = "name", length = 50, nullable = false)
    private String name;

    @Column(name = "display_label", length = 100, nullable = false)
    private String displayLabel;

    @Column(name = "is_system", nullable = false)
    private boolean isSystem;

    @Column(name = "created_at", nullable = false, updatable = false)
    private LocalDateTime createdAt;

    @Column(name = "updated_at", nullable = false)
    private LocalDateTime updatedAt;

    public RoleDefinition() {
    }

    /**
     * Factory method for creating a new role definition.
     * Validates the role name format (UPPER_SNAKE_CASE, 1-50 chars).
     */
    public static RoleDefinition create(String name, String displayLabel) {
        Objects.requireNonNull(name, "Role name must not be null");
        Objects.requireNonNull(displayLabel, "Display label must not be null");

        String trimmedName = name.trim();
        if (trimmedName.isEmpty() || trimmedName.length() > 50) {
            throw new IllegalArgumentException("Role name must be between 1 and 50 characters");
        }
        if (!trimmedName.matches("^[A-Z][A-Z0-9_]*$")) {
            throw new IllegalArgumentException("Role name must be UPPER_SNAKE_CASE (letters, digits, underscores, starting with a letter)");
        }

        String trimmedLabel = displayLabel.trim();
        if (trimmedLabel.isEmpty() || trimmedLabel.length() > 100) {
            throw new IllegalArgumentException("Display label must be between 1 and 100 characters");
        }

        var rd = new RoleDefinition();
        rd.name = trimmedName;
        rd.displayLabel = trimmedLabel;
        rd.isSystem = false;
        rd.createdAt = LocalDateTime.now();
        rd.updatedAt = LocalDateTime.now();
        return rd;
    }

    /**
     * Updates the display label. System roles cannot be renamed.
     */
    public void updateDisplayLabel(String newDisplayLabel) {
        Objects.requireNonNull(newDisplayLabel, "Display label must not be null");
        String trimmed = newDisplayLabel.trim();
        if (trimmed.isEmpty() || trimmed.length() > 100) {
            throw new IllegalArgumentException("Display label must be between 1 and 100 characters");
        }
        this.displayLabel = trimmed;
        this.updatedAt = LocalDateTime.now();
    }

    /**
     * Validates whether this role definition can be deleted.
     * System roles and roles currently assigned to users cannot be deleted.
     */
    public void validateCanDelete() {
        if (this.isSystem) {
            throw new SystemRoleModificationException(this.name);
        }
    }

    // --- Panache finders ---

    public static Optional<RoleDefinition> findByName(String name) {
        return find("name", name).firstResultOptional();
    }

    public static List<RoleDefinition> listAllOrdered() {
        return list("ORDER BY name");
    }

    /**
     * Counts how many users currently have this role assigned.
     */
    public long countUsages() {
        return Role.count("role", this.name);
    }

    // --- Getters ---

    public String getName() {
        return name;
    }

    public String getDisplayLabel() {
        return displayLabel;
    }

    public boolean isSystem() {
        return isSystem;
    }

    public LocalDateTime getCreatedAt() {
        return createdAt;
    }

    public LocalDateTime getUpdatedAt() {
        return updatedAt;
    }

    // --- Domain exception ---

    public static class SystemRoleModificationException extends RuntimeException {
        private final String roleName;

        public SystemRoleModificationException(String roleName) {
            super("System role '" + roleName + "' cannot be deleted or modified");
            this.roleName = roleName;
        }

        public String getRoleName() {
            return roleName;
        }
    }

    public static class RoleInUseException extends RuntimeException {
        private final String roleName;
        private final long usageCount;

        public RoleInUseException(String roleName, long usageCount) {
            super("Role '" + roleName + "' is currently assigned to " + usageCount + " user(s) and cannot be deleted");
            this.roleName = roleName;
            this.usageCount = usageCount;
        }

        public String getRoleName() {
            return roleName;
        }

        public long getUsageCount() {
            return usageCount;
        }
    }
}
