package dk.trustworks.intranet.recruitmentservice.model;

import com.fasterxml.jackson.annotation.JsonProperty;
import dk.trustworks.intranet.model.Auditable;
import dk.trustworks.intranet.security.AuditEntityListener;
import io.quarkus.hibernate.orm.panache.PanacheEntityBase;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EntityListeners;
import jakarta.persistence.Id;
import jakarta.persistence.PrePersist;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.time.LocalDateTime;
import java.util.Locale;

/**
 * One meeting room's standing in AI-assisted interview scheduling: may the
 * planners book it, and how strongly do they prefer it (V513).
 * <p>
 * The row is a POLICY, not a room. Rooms themselves live in Microsoft Graph
 * ({@code /places/microsoft.graph.room}) and are never mastered here — this
 * table only answers "may automation use it" and "in what order", keyed by
 * the room mailbox because that is the identity Graph, the calendar invite
 * and the planners all agree on. {@link #displayName} is a refreshed
 * snapshot for the settings UI, so a room can still be named when Graph is
 * unreachable or the resource has been deleted from the tenant.
 * <p>
 * {@link #enabled} defaults to false: a room created in Exchange after the
 * initial seed must be ranked deliberately rather than quietly joining the
 * rotation. The empty-table seed that keeps this from breaking day one lives
 * in {@link dk.trustworks.intranet.recruitmentservice.services.RecruitmentMeetingRoomPolicyService}.
 * <p>
 * The flag governs AUTOMATION only. A recruiter picking a room by hand in
 * the Schedule Interview dialog still sees every room the tenant has.
 */
@Getter
@Setter
@NoArgsConstructor
@Entity
@Table(name = "recruitment_meeting_room_policy")
@EntityListeners(AuditEntityListener.class)
public class RecruitmentMeetingRoomPolicy extends PanacheEntityBase implements Auditable {

    /** Graph room mailbox, lowercase — the identity. */
    @Id
    @Column(name = "room_email", length = 255, nullable = false, updatable = false)
    private String roomEmail;

    /** Last seen Graph displayName. Snapshot only — never matched on. */
    @Column(name = "display_name", length = 255)
    private String displayName;

    /** Whether the AI planners may choose this room. */
    @Column(name = "enabled", nullable = false)
    private boolean enabled;

    /** 1-based preference order, ascending = preferred. */
    @Column(name = "priority", nullable = false)
    private int priority;

    // ---- Audit columns (house Auditable pattern) ---------------------------

    @Column(name = "created_at", nullable = false, updatable = false)
    @JsonProperty(access = JsonProperty.Access.READ_ONLY)
    private LocalDateTime createdAt;

    @Column(name = "updated_at", nullable = false)
    @JsonProperty(access = JsonProperty.Access.READ_ONLY)
    private LocalDateTime updatedAt;

    @Column(name = "created_by")
    @JsonProperty(access = JsonProperty.Access.READ_ONLY)
    private String createdBy;

    @Column(name = "modified_by")
    @JsonProperty(access = JsonProperty.Access.READ_ONLY)
    private String modifiedBy;

    public RecruitmentMeetingRoomPolicy(String roomEmail, String displayName,
                                        boolean enabled, int priority) {
        this.roomEmail = normalizeEmail(roomEmail);
        this.displayName = displayName;
        this.enabled = enabled;
        this.priority = priority;
    }

    /**
     * The one place a room mailbox becomes a key. Graph echoes addresses in
     * whatever case the tenant stored them, and the planners compare against
     * lowercased schedule keys — a mixed-case row would silently never match
     * its own room.
     */
    public static String normalizeEmail(String email) {
        return email == null ? null : email.trim().toLowerCase(Locale.ROOT);
    }

    @PrePersist
    void onCreate() {
        roomEmail = normalizeEmail(roomEmail);
    }
}
