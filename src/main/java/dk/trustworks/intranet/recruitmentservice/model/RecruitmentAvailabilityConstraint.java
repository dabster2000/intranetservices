package dk.trustworks.intranet.recruitmentservice.model;

import com.fasterxml.jackson.annotation.JsonProperty;
import dk.trustworks.intranet.recruitmentservice.model.enums.AvailabilityConstraintHardness;
import dk.trustworks.intranet.recruitmentservice.model.enums.AvailabilityConstraintType;
import io.quarkus.hibernate.orm.panache.PanacheEntityBase;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.PrePersist;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.time.LocalDateTime;
import java.util.UUID;

/**
 * One normalized availability interval extracted from one evidence row
 * (plan §12.1). Wall-clock Europe/Copenhagen, end-exclusive, immutable
 * once written — corrections create NEW evidence, they never edit old
 * intervals (the supersede chain is the history).
 */
@Getter
@Setter
@NoArgsConstructor
@Entity
@Table(name = "recruitment_availability_constraint")
public class RecruitmentAvailabilityConstraint extends PanacheEntityBase {

    @Id
    @Column(name = "uuid", length = 36, nullable = false, updatable = false)
    private String uuid;

    @Column(name = "evidence_uuid", length = 36, nullable = false, updatable = false)
    private String evidenceUuid;

    @Enumerated(EnumType.STRING)
    @Column(name = "type", length = 15, nullable = false, updatable = false)
    private AvailabilityConstraintType type;

    @Column(name = "start_at", nullable = false, updatable = false)
    private LocalDateTime startAt;

    @Column(name = "end_at", nullable = false, updatable = false)
    private LocalDateTime endAt;

    /** Audit surface in v1 — planning derives hardness from type. */
    @Enumerated(EnumType.STRING)
    @Column(name = "hardness", length = 4, nullable = false, updatable = false)
    private AvailabilityConstraintHardness hardness = AvailabilityConstraintHardness.HARD;

    @Column(name = "created_at", nullable = false, updatable = false)
    @JsonProperty(access = JsonProperty.Access.READ_ONLY)
    private LocalDateTime createdAt = LocalDateTime.now();

    @PrePersist
    protected void onCreate() {
        if (uuid == null) {
            uuid = UUID.randomUUID().toString();
        }
    }
}
