package dk.trustworks.intranet.recruitmentservice.model;

import com.fasterxml.jackson.annotation.JsonProperty;
import dk.trustworks.intranet.model.Auditable;
import dk.trustworks.intranet.recruitmentservice.model.enums.RecruitmentInterviewKind;
import dk.trustworks.intranet.recruitmentservice.model.enums.RecruitmentInterviewStatus;
import dk.trustworks.intranet.recruitmentservice.model.enums.RecruitmentStage;
import dk.trustworks.intranet.security.AuditEntityListener;
import io.quarkus.hibernate.orm.panache.PanacheEntityBase;
import jakarta.persistence.Column;
import jakarta.persistence.Convert;
import jakarta.persistence.Entity;
import jakarta.persistence.EntityListeners;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.PrePersist;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;

/**
 * One interview on one application (ATS spec §4.1/§5.3): rounds 1–3 count
 * toward the stage machine (round <em>n</em> ↔ stage {@code INTERVIEW_n});
 * {@code INFORMAL} is the schedulable <em>uformel snak</em> that never
 * advances the stage and takes no scorecard, and {@code OFFER} is the
 * offer-phase meeting — schedulable on the same terms as an informal chat
 * (any time the application is in play), no round, no scorecard.
 * <p>
 * State changes are only made through {@code RecruitmentInterviewService},
 * which pairs every mutation with its {@code INTERVIEW_*} event. Interviewer
 * assignment ({@link #interviewerUuids}) grants per-candidate involvement in
 * {@code RecruitmentVisibility} (spec §7.2 "Interviewer = per-candidate
 * assignment, not a standing role").
 */
@Getter
@Setter
@NoArgsConstructor
@Entity
@Table(name = "recruitment_interviews")
@EntityListeners(AuditEntityListener.class)
public class RecruitmentInterview extends PanacheEntityBase implements Auditable {

    @Id
    @Column(name = "uuid", length = 36, nullable = false, updatable = false)
    private String uuid;

    /** FK to {@code recruitment_applications.uuid}. */
    @Column(name = "application_uuid", length = 36, nullable = false, updatable = false)
    private String applicationUuid;

    @Enumerated(EnumType.STRING)
    @Column(name = "kind", length = 10, nullable = false, updatable = false)
    private RecruitmentInterviewKind kind;

    /** 1..3 for {@code ROUND}; {@code NULL} for every other kind. */
    @Column(name = "round", updatable = false)
    private Integer round;

    /** Wall-clock Europe/Copenhagen as entered by the scheduler. */
    @Column(name = "scheduled_at")
    private LocalDateTime scheduledAt;

    /**
     * Interview length in minutes (V474). Drives the Outlook event end
     * time and the free/busy window probed for rooms and interviewers.
     * Defaults to 60 — the length every pre-V474 row was booked with.
     */
    @Column(name = "duration_minutes", nullable = false)
    private int durationMinutes = 60;

    /**
     * Outlook linkage when Graph calendar scheduling is enabled
     * ({@code dk.trustworks.recruitment.graph.calendar.enabled});
     * {@code NULL} under manual scheduling (plan §P11 fallback).
     */
    @Column(name = "graph_event_id", length = 255)
    @JsonProperty(access = JsonProperty.Access.READ_ONLY)
    private String graphEventId;

    /**
     * The mailbox the Graph event was created under (V492). Update and
     * cancel MUST address this mailbox — deriving the organizer from the
     * current interviewer list breaks the moment interviewer #1 is
     * removed on a reschedule. {@code NULL} when no event exists.
     */
    @Column(name = "graph_organizer", length = 255)
    @JsonProperty(access = JsonProperty.Access.READ_ONLY)
    private String graphOrganizer;

    /**
     * The candidate-facing Outlook event's Graph id (V493 two-event
     * split). {@code NULL} = single-event row: created before the split,
     * or the candidate has no email. Update/cancel treat NULL rows
     * exactly as before the split — nothing migrates, nobody gets
     * double-invited.
     */
    @Column(name = "graph_candidate_event_id", length = 255)
    @JsonProperty(access = JsonProperty.Access.READ_ONLY)
    private String graphCandidateEventId;

    /** Whether the Outlook event is a Teams meeting (V492). */
    @Column(name = "online_meeting", nullable = false)
    private boolean onlineMeeting;

    /** The Teams join link Graph returned; {@code NULL} when not a Teams
     * meeting (or the link has not been read back yet). */
    @Column(name = "join_url", length = 1024)
    @JsonProperty(access = JsonProperty.Access.READ_ONLY)
    private String joinUrl;

    /** Soft FKs to {@code users.uuid} — the assigned interviewers. */
    @Convert(converter = StringListConverter.class)
    @Column(name = "interviewer_uuids", columnDefinition = "JSON", nullable = false)
    private List<String> interviewerUuids;

    /** PII-free: room name or {@code "Teams"} (spec §4.1). */
    @Column(name = "location", length = 200)
    private String location;

    /**
     * Room mailbox (Graph {@code /places/microsoft.graph.room}) invited as
     * a {@code "resource"} attendee so the Outlook event books the room;
     * {@code NULL} = no room booked. Persisted so a reschedule — which
     * rebuilds the full attendee list — keeps and moves the booking.
     */
    @Column(name = "room_email", length = 255)
    private String roomEmail;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", length = 10, nullable = false)
    private RecruitmentInterviewStatus status = RecruitmentInterviewStatus.SCHEDULED;

    // ---- Audit columns (house Auditable pattern) ---------------------------

    @Column(name = "created_at", nullable = false, updatable = false)
    @JsonProperty(access = JsonProperty.Access.READ_ONLY)
    private LocalDateTime createdAt;

    @Column(name = "updated_at", nullable = false)
    @JsonProperty(access = JsonProperty.Access.READ_ONLY)
    private LocalDateTime updatedAt;

    @Column(name = "created_by", nullable = false, updatable = false)
    @JsonProperty(access = JsonProperty.Access.READ_ONLY)
    private String createdBy;

    @Column(name = "modified_by")
    @JsonProperty(access = JsonProperty.Access.READ_ONLY)
    private String modifiedBy;

    @PrePersist
    protected void onCreate() {
        if (uuid == null) {
            uuid = UUID.randomUUID().toString();
        }
        if (status == null) {
            status = RecruitmentInterviewStatus.SCHEDULED;
        }
    }

    /** @return true iff this interview may still be acted on. */
    public boolean isActive() {
        return status != RecruitmentInterviewStatus.CANCELLED;
    }

    /**
     * The pipeline stage this interview belongs to — {@code INTERVIEW_n}
     * for round <em>n</em>, {@code null} for {@code INFORMAL} and
     * {@code OFFER} (neither maps to a round in the stage machine; an
     * offer meeting is scheduled around the OFFER stage, it does not
     * gate it). The blind rule's "after decision" unlock compares the
     * application's current stage against this, and every round-only
     * sweep (SLA nudges, pending-decision tasks) treats a {@code null}
     * here as "not an evaluated round".
     */
    public RecruitmentStage roundStage() {
        if (kind == null || !kind.hasRound() || round == null) {
            return null;
        }
        return switch (round) {
            case 1 -> RecruitmentStage.INTERVIEW_1;
            case 2 -> RecruitmentStage.INTERVIEW_2;
            case 3 -> RecruitmentStage.INTERVIEW_3;
            default -> null;
        };
    }

    /** Whether the given user is one of the assigned interviewers. */
    public boolean isAssigned(String userUuid) {
        return userUuid != null && interviewerUuids != null && interviewerUuids.contains(userUuid);
    }
}
