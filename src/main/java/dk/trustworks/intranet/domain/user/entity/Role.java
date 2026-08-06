package dk.trustworks.intranet.domain.user.entity;


import com.fasterxml.jackson.annotation.JsonIgnore;
import com.fasterxml.jackson.annotation.JsonProperty;
import dk.trustworks.intranet.model.Auditable;
import dk.trustworks.intranet.security.AuditEntityListener;
import io.quarkus.hibernate.orm.panache.PanacheEntityBase;

import jakarta.persistence.*;
import java.time.LocalDateTime;
import java.util.List;

/**
 * Created by hans on 23/06/2017.
 */
@Entity
@Table(name = "roles")
@EntityListeners(AuditEntityListener.class)
public class Role extends PanacheEntityBase implements Auditable {

    @Id
    private String uuid;

    @Column(name = "role")
    private String role;

    @JsonIgnore
    private String useruuid;

    // Audit columns (V469, Phase 7 task 7.1). Nullable — rows predating the
    // migration have no recorded actor. @JsonIgnore, not @JsonProperty(READ_ONLY):
    // Role is serialized by the @PermitAll /login endpoints, and the Phase 3
    // public-surface snapshot rightly refuses new fields there. The row-level
    // audit is database evidence for the console; the API trail is authz_audit.
    @Column(name = "created_at", updatable = false)
    @JsonIgnore
    private LocalDateTime createdAt;

    @Column(name = "created_by", updatable = false)
    @JsonIgnore
    private String createdBy;

    @Column(name = "updated_at")
    @JsonIgnore
    private LocalDateTime updatedAt;

    @Column(name = "modified_by")
    @JsonIgnore
    private String modifiedBy;

    public Role() {
    }

    public Role(String uuid, String role, String useruuid) {
        this.uuid = uuid;
        this.role = role;
        this.useruuid = useruuid;
    }

    public Role(String role) {
        this.role = role;
    }

    public String getUuid() {
        return uuid;
    }

    public void setUuid(String uuid) {
        this.uuid = uuid;
    }

    public String getRole() {
        return role;
    }

    public void setRole(String role) {
        this.role = role;
    }

    public String getUseruuid() {
        return useruuid;
    }

    public void setUseruuid(String useruuid) {
        this.useruuid = useruuid;
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

    public static List<Role> findByUseruuid(String useruuid){
        return find("useruuid", useruuid).list();
    }

    @Override
    public String toString() {
        return "Role{" +
                "uuid='" + uuid + '\'' +
                ", role=" + role +
                ", useruuid='" + useruuid + '\'' +
                '}';
    }
}
