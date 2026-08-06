package dk.trustworks.intranet.security;

import com.fasterxml.jackson.annotation.JsonProperty;
import dk.trustworks.intranet.model.Auditable;
import io.quarkus.hibernate.orm.panache.PanacheEntityBase;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EntityListeners;
import jakarta.persistence.Id;
import jakarta.persistence.IdClass;
import jakarta.persistence.Table;

import java.io.Serializable;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

/**
 * A role → permission binding in the Phase 4 catalogue (`role_permission`).
 *
 * <p>Phase 4 deliberately shipped these tables without Hibernate entities while the
 * catalogue was dormant. Phase 7 makes the bindings UI-managed, so the editor write
 * path needs an entity — reads for authorization decisions stay on
 * {@link DbAuthzStore}'s native SQL.
 *
 * <p><strong>Revocation is a tombstone, never a DELETE</strong> (F-13): a deleted row
 * would be resurrected by a seed re-run after a rollback. {@code revoked_at IS NULL}
 * is the "active" predicate everywhere.
 */
@Entity
@Table(name = "role_permission")
@EntityListeners(AuditEntityListener.class)
@IdClass(RolePermission.PK.class)
public class RolePermission extends PanacheEntityBase implements Auditable {

    @Id
    @Column(name = "role")
    private String role;

    @Id
    @Column(name = "permission_key")
    private String permissionKey;

    @Column(name = "granted_by")
    private String grantedBy;

    @Column(name = "granted_at")
    private LocalDateTime grantedAt;

    @Column(name = "revoked_at")
    private LocalDateTime revokedAt;

    @Column(name = "created_at", updatable = false)
    @JsonProperty(access = JsonProperty.Access.READ_ONLY)
    private LocalDateTime createdAt;

    @Column(name = "created_by", updatable = false)
    @JsonProperty(access = JsonProperty.Access.READ_ONLY)
    private String createdBy;

    @Column(name = "updated_at")
    @JsonProperty(access = JsonProperty.Access.READ_ONLY)
    private LocalDateTime updatedAt;

    @Column(name = "modified_by")
    @JsonProperty(access = JsonProperty.Access.READ_ONLY)
    private String modifiedBy;

    public RolePermission() {
    }

    public RolePermission(String role, String permissionKey) {
        this.role = role;
        this.permissionKey = permissionKey;
    }

    public static Optional<RolePermission> findByRoleAndKey(String role, String permissionKey) {
        return find("role = ?1 and permissionKey = ?2", role, permissionKey).firstResultOptional();
    }

    public static List<RolePermission> listActive() {
        return list("revokedAt is null");
    }

    public boolean isActive() {
        return revokedAt == null;
    }

    public String getRole() {
        return role;
    }

    public String getPermissionKey() {
        return permissionKey;
    }

    public String getGrantedBy() {
        return grantedBy;
    }

    public void setGrantedBy(String grantedBy) {
        this.grantedBy = grantedBy;
    }

    public LocalDateTime getGrantedAt() {
        return grantedAt;
    }

    public void setGrantedAt(LocalDateTime grantedAt) {
        this.grantedAt = grantedAt;
    }

    public LocalDateTime getRevokedAt() {
        return revokedAt;
    }

    public void setRevokedAt(LocalDateTime revokedAt) {
        this.revokedAt = revokedAt;
    }

    @Override
    public LocalDateTime getCreatedAt() {
        return createdAt;
    }

    @Override
    public void setCreatedAt(LocalDateTime createdAt) {
        this.createdAt = createdAt;
    }

    @Override
    public LocalDateTime getUpdatedAt() {
        return updatedAt;
    }

    @Override
    public void setUpdatedAt(LocalDateTime updatedAt) {
        this.updatedAt = updatedAt;
    }

    @Override
    public String getCreatedBy() {
        return createdBy;
    }

    @Override
    public void setCreatedBy(String createdBy) {
        this.createdBy = createdBy;
    }

    @Override
    public String getModifiedBy() {
        return modifiedBy;
    }

    @Override
    public void setModifiedBy(String modifiedBy) {
        this.modifiedBy = modifiedBy;
    }

    /** Composite primary key: (role, permission_key). */
    public static class PK implements Serializable {
        private String role;
        private String permissionKey;

        public PK() {
        }

        public PK(String role, String permissionKey) {
            this.role = role;
            this.permissionKey = permissionKey;
        }

        @Override
        public boolean equals(Object o) {
            if (this == o) return true;
            if (!(o instanceof PK pk)) return false;
            return Objects.equals(role, pk.role) && Objects.equals(permissionKey, pk.permissionKey);
        }

        @Override
        public int hashCode() {
            return Objects.hash(role, permissionKey);
        }
    }
}
