package dk.trustworks.intranet.recruitmentservice.domain.entities;

import dk.trustworks.intranet.recruitmentservice.domain.enums.RecruitmentStatusValue;
import dk.trustworks.intranet.recruitmentservice.domain.enums.ScopeKind;
import io.quarkus.hibernate.orm.panache.PanacheEntityBase;
import jakarta.persistence.*;
import lombok.Data;

import java.io.Serializable;
import java.time.LocalDateTime;
import java.util.Objects;

@Data
@Entity
@Table(name = "recruitment_status")
@IdClass(RecruitmentStatusEntity.Id.class)
public class RecruitmentStatusEntity extends PanacheEntityBase {

    @jakarta.persistence.Id
    @Enumerated(EnumType.STRING)
    @Column(name = "scope_kind", length = 20, nullable = false)
    public ScopeKind scopeKind;

    @jakarta.persistence.Id
    @Column(name = "scope_id", length = 40, nullable = false)
    public String scopeId;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", length = 20, nullable = false)
    public RecruitmentStatusValue status;

    @Column(name = "changed_by_uuid", length = 36, nullable = false)
    public String changedByUuid;

    @Column(name = "changed_at", nullable = false)
    public LocalDateTime changedAt = LocalDateTime.now();

    @Column(name = "reason", columnDefinition = "TEXT")
    public String reason;

    public static class Id implements Serializable {
        public ScopeKind scopeKind;
        public String scopeId;

        public Id() {}
        public Id(ScopeKind scopeKind, String scopeId) {
            this.scopeKind = scopeKind;
            this.scopeId = scopeId;
        }

        @Override public boolean equals(Object o) {
            if (this == o) return true;
            if (!(o instanceof Id id)) return false;
            return scopeKind == id.scopeKind && Objects.equals(scopeId, id.scopeId);
        }
        @Override public int hashCode() { return Objects.hash(scopeKind, scopeId); }
    }
}
