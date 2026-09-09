package dk.trustworks.intranet.recruitmentservice.model;

import com.fasterxml.jackson.annotation.JsonProperty;
import io.quarkus.hibernate.orm.panache.PanacheEntityBase;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.PrePersist;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.UUID;

/**
 * One assignment of a recruitment assistant to a position — the record-level
 * scope of the {@code RECRUITMENT_ASSISTANT} role since the 2026-09-08
 * position-scoping design (D1). Before it, the role was scoped to the
 * viewer's {@code user.practice_uuid}; that route is gone entirely.
 * <p>
 * {@code RecruitmentVisibility} is still the authority — it reads these rows
 * through {@link #activePositionUuidsFor(String)} and never trusts a caller
 * to have checked. An assignment is <b>data, not a permission</b>: no
 * {@code role_permission} key corresponds to it, and holding the role with no
 * assignment row grants nothing at all.
 *
 * <h2>Why this is not a circle seat</h2>
 * A {@code RecruitmentCircleMember} row carries its own grants — including
 * the only route into {@code PARTNER}-track positions — so reusing it with a
 * new {@code role_in_circle} value would have handed assistants exactly the
 * access the design withholds. Deliberately a separate table.
 *
 * <h2>Soft revoke</h2>
 * Removing an assistant sets {@link #revokedAt}; rows are never deleted, so
 * "who could see this candidate in August?" stays answerable. That diverges
 * from the platform's other tombstone ({@code role_permission} keeps one row
 * per pair forever and un-revokes it in place) and the divergence is
 * intentional — see the spec §5. The unique key
 * {@code (position_uuid, user_uuid, revoked_at)} permits one active row plus
 * unlimited revoked ones, because MariaDB treats NULLs as distinct.
 */
@Getter
@Setter
@NoArgsConstructor
@Entity
@Table(name = "recruitment_position_assistants")
public class RecruitmentPositionAssistant extends PanacheEntityBase {

    @Id
    @Column(name = "uuid", length = 36, nullable = false, updatable = false)
    private String uuid;

    @Column(name = "position_uuid", length = 36, nullable = false, updatable = false)
    private String positionUuid;

    @Column(name = "user_uuid", length = 36, nullable = false, updatable = false)
    private String userUuid;

    /** Soft FK to {@code users.uuid} — who granted the assignment. */
    @Column(name = "assigned_by", length = 36, nullable = false, updatable = false)
    @JsonProperty(access = JsonProperty.Access.READ_ONLY)
    private String assignedBy;

    @Column(name = "assigned_at", nullable = false, updatable = false)
    @JsonProperty(access = JsonProperty.Access.READ_ONLY)
    private LocalDateTime assignedAt;

    /** Soft FK to {@code users.uuid} — who revoked it. Null while active. */
    @Column(name = "revoked_by", length = 36)
    @JsonProperty(access = JsonProperty.Access.READ_ONLY)
    private String revokedBy;

    /** Null while active; the tombstone timestamp once revoked. */
    @Column(name = "revoked_at")
    @JsonProperty(access = JsonProperty.Access.READ_ONLY)
    private LocalDateTime revokedAt;

    public RecruitmentPositionAssistant(String positionUuid, String userUuid, String assignedBy) {
        this.positionUuid = positionUuid;
        this.userUuid = userUuid;
        this.assignedBy = assignedBy;
    }

    @PrePersist
    protected void onCreate() {
        if (uuid == null) {
            uuid = UUID.randomUUID().toString();
        }
        if (assignedAt == null) {
            assignedAt = LocalDateTime.now(ZoneOffset.UTC);
        }
    }

    public boolean isActive() {
        return revokedAt == null;
    }

    /** Marks the assignment revoked. Idempotent — a second call is a no-op. */
    public void revoke(String revokedByUuid) {
        if (revokedAt == null) {
            revokedAt = LocalDateTime.now(ZoneOffset.UTC);
            revokedBy = revokedByUuid;
        }
    }

    /** The active assignment for this pair, or null. */
    public static RecruitmentPositionAssistant findActive(String positionUuid, String userUuid) {
        return find("positionUuid = ?1 and userUuid = ?2 and revokedAt is null",
                positionUuid, userUuid).firstResult();
    }

    /** Every active assignment on a position, oldest first. */
    public static java.util.List<RecruitmentPositionAssistant> activeOnPosition(String positionUuid) {
        return list("positionUuid = ?1 and revokedAt is null order by assignedAt", positionUuid);
    }
}
