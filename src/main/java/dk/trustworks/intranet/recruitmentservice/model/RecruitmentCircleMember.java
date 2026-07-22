package dk.trustworks.intranet.recruitmentservice.model;

import com.fasterxml.jackson.annotation.JsonProperty;
import dk.trustworks.intranet.recruitmentservice.model.enums.RecruitmentCircleRole;
import io.quarkus.hibernate.orm.panache.PanacheEntityBase;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.IdClass;
import jakarta.persistence.PrePersist;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.io.Serializable;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.Objects;

/**
 * Membership row of a partner-track position's circle (ATS spec §4.1) —
 * the confidentiality boundary: a {@code PARTNER}-track position is visible
 * <em>only</em> to its circle members (plus admins), enforced by
 * {@code RecruitmentVisibility} in every query path.
 * <p>
 * Composite key (position, user): a user is in a circle at most once.
 * Mutations go through {@code RecruitmentPositionService}, which pairs them
 * with {@code CIRCLE_MEMBER_ADDED/REMOVED} events.
 */
@Getter
@Setter
@NoArgsConstructor
@Entity
@Table(name = "recruitment_circle_members")
@IdClass(RecruitmentCircleMember.Key.class)
public class RecruitmentCircleMember extends PanacheEntityBase {

    @Id
    @Column(name = "position_uuid", length = 36, nullable = false, updatable = false)
    private String positionUuid;

    @Id
    @Column(name = "user_uuid", length = 36, nullable = false, updatable = false)
    private String userUuid;

    @Enumerated(EnumType.STRING)
    @Column(name = "role_in_circle", length = 15, nullable = false)
    private RecruitmentCircleRole roleInCircle = RecruitmentCircleRole.PARTICIPANT;

    @Column(name = "added_at", nullable = false, updatable = false)
    @JsonProperty(access = JsonProperty.Access.READ_ONLY)
    private LocalDateTime addedAt;

    /** Soft FK to {@code users.uuid} — who added this member. */
    @Column(name = "added_by_uuid", length = 36, nullable = false, updatable = false)
    @JsonProperty(access = JsonProperty.Access.READ_ONLY)
    private String addedByUuid;

    public RecruitmentCircleMember(String positionUuid, String userUuid,
                                   RecruitmentCircleRole roleInCircle, String addedByUuid) {
        this.positionUuid = positionUuid;
        this.userUuid = userUuid;
        this.roleInCircle = roleInCircle;
        this.addedByUuid = addedByUuid;
    }

    @PrePersist
    protected void onCreate() {
        if (addedAt == null) {
            addedAt = LocalDateTime.now(ZoneOffset.UTC);
        }
        if (roleInCircle == null) {
            roleInCircle = RecruitmentCircleRole.PARTICIPANT;
        }
    }

    /** Composite primary key: (position, user). */
    @NoArgsConstructor
    @Getter
    @Setter
    public static class Key implements Serializable {

        private String positionUuid;
        private String userUuid;

        public Key(String positionUuid, String userUuid) {
            this.positionUuid = positionUuid;
            this.userUuid = userUuid;
        }

        @Override
        public boolean equals(Object o) {
            if (this == o) return true;
            if (!(o instanceof Key key)) return false;
            return Objects.equals(positionUuid, key.positionUuid)
                    && Objects.equals(userUuid, key.userUuid);
        }

        @Override
        public int hashCode() {
            return Objects.hash(positionUuid, userUuid);
        }
    }
}
