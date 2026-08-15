package dk.trustworks.intranet.recruitmentservice.model;

import com.fasterxml.jackson.annotation.JsonProperty;
import dk.trustworks.intranet.model.Auditable;
import dk.trustworks.intranet.recruitmentservice.model.enums.RecruitmentInterviewKind;
import dk.trustworks.intranet.recruitmentservice.model.enums.SchedulingRequestStatus;
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
import jakarta.persistence.Version;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.util.List;
import java.util.UUID;

/**
 * One Method B candidate-option scheduling run on one application
 * (plan 2026-08-12 §8.1, spec §15). The row IS the workflow: the
 * advance sweep and the Slack/candidate inbound events move
 * {@link #status} through {@code SchedulingStateMachine}-approved
 * transitions, each in one short transaction with one audit event —
 * a deploy mid-step costs nothing.
 * <p>
 * Interview parameters mirror the Method A create path so finalization
 * (Phase 11) can call the existing interview-create internals verbatim.
 */
@Getter
@Setter
@NoArgsConstructor
@Entity
@Table(name = "recruitment_scheduling_request")
@EntityListeners(AuditEntityListener.class)
public class RecruitmentSchedulingRequest extends PanacheEntityBase implements Auditable {

    @Id
    @Column(name = "uuid", length = 36, nullable = false, updatable = false)
    private String uuid;

    /** FK to {@code recruitment_applications.uuid}. */
    @Column(name = "application_uuid", length = 36, nullable = false, updatable = false)
    private String applicationUuid;

    /** Soft FK users.uuid — who started Method B; gets escalations. */
    @Column(name = "recruiter_uuid", length = 36, nullable = false, updatable = false)
    private String recruiterUuid;

    // ---- Interview parameters (mirror the Method A create path) ----------

    @Enumerated(EnumType.STRING)
    @Column(name = "kind", length = 10, nullable = false, updatable = false)
    private RecruitmentInterviewKind kind;

    /** 1..3 for ROUND; NULL for INFORMAL. */
    @Column(name = "round", updatable = false)
    private Integer round;

    @Column(name = "duration_minutes", nullable = false)
    private int durationMinutes = 60;

    @Column(name = "online_meeting", nullable = false)
    private boolean onlineMeeting;

    /** Only slots with a bookable free room qualify. */
    @Column(name = "require_room", nullable = false)
    private boolean requireRoom;

    /** PII-free free-text location fallback, as on interviews. */
    @Column(name = "location", length = 200)
    private String location;

    /** Soft FKs users.uuid — REQUIRED interviewers (each must approve). */
    @Convert(converter = StringListConverter.class)
    @Column(name = "interviewer_uuids", columnDefinition = "JSON", nullable = false)
    private List<String> interviewerUuids;

    /** Soft FKs users.uuid — optional interviewers (defaults §29.7:
     * never block a slot; slots where they are free rank higher). */
    @Convert(converter = StringListConverter.class)
    @Column(name = "optional_interviewer_uuids", columnDefinition = "JSON")
    private List<String> optionalInterviewerUuids;

    // ---- Method B option parameters --------------------------------------

    /** 1–3 options to secure. */
    @Column(name = "requested_options", nullable = false)
    private int requestedOptions = 3;

    @Column(name = "window_start", nullable = false)
    private LocalDate windowStart;

    /** Inclusive. */
    @Column(name = "window_end", nullable = false)
    private LocalDate windowEnd;

    @Column(name = "permitted_start")
    private LocalTime permittedStart;

    @Column(name = "permitted_end")
    private LocalTime permittedEnd;

    @Column(name = "min_separation_hours", nullable = false)
    private int minSeparationHours;

    @Column(name = "different_days", nullable = false)
    private boolean differentDays;

    /** Explicit candidate answer-by override; NULL = computed at send
     * time (3 business days after the options go out, defaults §29.2). */
    @Column(name = "candidate_deadline")
    private LocalDateTime candidateDeadline;

    /** The two-week anchor (defaults §29.18): unfinished by here ⇒
     * holds released, request handed back. */
    @Column(name = "automation_deadline", nullable = false)
    private LocalDateTime automationDeadline;

    /** D11: recruiter reviews & sends the secured options. Default ON. */
    @Column(name = "review_required", nullable = false)
    private boolean reviewRequired = true;

    /** Recruiter-sends-the-link mode (owner request 2026-08-15): the
     * send step mails the candidate NOTHING — the recruiter gets the
     * tokenized link + a ready-to-send text draft as a Slack DM. */
    @Column(name = "manual_candidate_delivery", nullable = false)
    private boolean manualCandidateDelivery = false;

    /** When the recruiter approved the batch for sending (D11). */
    @Column(name = "options_approved_at")
    @JsonProperty(access = JsonProperty.Access.READ_ONLY)
    private LocalDateTime optionsApprovedAt;

    // ---- Workflow state ---------------------------------------------------

    @Enumerated(EnumType.STRING)
    @Column(name = "status", length = 26, nullable = false)
    private SchedulingRequestStatus status = SchedulingRequestStatus.DRAFT;

    /** Structural text only — never candidate PII. */
    @Column(name = "handback_reason", length = 1000)
    private String handbackReason;

    /** Advance-sweep pacing: NULL = act on the next sweep; a future
     * time = wait (search retry backoff). */
    @Column(name = "next_action_at")
    @JsonProperty(access = JsonProperty.Access.READ_ONLY)
    private LocalDateTime nextActionAt;

    /** JPA optimistic lock — sweeps, handlers and the public selection
     * path race safely; the loser retries against fresh state. */
    @Version
    @Column(name = "version", nullable = false)
    private int version;

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
            status = SchedulingRequestStatus.DRAFT;
        }
    }
}
