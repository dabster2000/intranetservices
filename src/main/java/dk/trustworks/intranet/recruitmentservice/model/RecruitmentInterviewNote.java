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
import java.util.UUID;

/**
 * One interviewer's LIVE note draft for one interview — the Interview
 * Room's autosave target (design spec 2026-08-26 §4.1, decision 2). One
 * row per (interview, author), created on first keystroke, deleted on
 * land; the durable artefact is the {@code SCORECARD_SUBMITTED} event,
 * never this row. Drafts for interviews that never landed are swept 30
 * days after {@code scheduled_at}.
 * <p>
 * {@code lines} is the whole draft as one JSON array of
 * {@code INoteLine} objects ({@code id, text, subjectCode, factField,
 * source, verbatim, ts}) — always read and written whole; the array IS
 * the contract (spec §3.3), the stable per-line {@code id} its
 * non-negotiable part. Kept as a raw JSON string on purpose: the backend
 * never interprets individual lines, it stores, returns and deletes them.
 * <p>
 * {@code clientRevision} is a last-write-wins guard, not a merge: a PUT
 * carrying a LOWER revision than stored answers 409 and the room offers
 * to reload. There is no CRDT and no operational transform (decision 7).
 * <p>
 * <b>GDPR — anonymisation target five (spec §4.4):</b> these drafts are
 * one person's written impressions of another, the most sensitive prose
 * in the module. {@code RecruitmentAnonymizerService} DELETES the rows
 * (a draft has no structural value worth preserving) and the DSAR export
 * includes them; a structure test fails if either leg goes missing.
 */
@Getter
@Setter
@NoArgsConstructor
@Entity
@Table(name = "recruitment_interview_notes")
@EntityListeners(AuditEntityListener.class)
public class RecruitmentInterviewNote extends PanacheEntityBase implements Auditable {

    @Id
    @Column(name = "uuid", length = 36, nullable = false, updatable = false)
    private String uuid;

    /** FK to {@code recruitment_interviews.uuid}. */
    @Column(name = "interview_uuid", length = 36, nullable = false, updatable = false)
    private String interviewUuid;

    /** Soft FK to {@code users.uuid} — whose private draft this is. */
    @Column(name = "author_uuid", length = 36, nullable = false, updatable = false)
    private String authorUuid;

    /** The whole draft: {@code INoteLine[]} as one JSON document. The
     * column is {@code note_lines} — LINES is a MariaDB reserved word. */
    @Column(name = "note_lines", columnDefinition = "JSON", nullable = false)
    private String lines;

    /** Monotonic, client-assigned. Lower-than-stored PUT ⇒ 409. */
    @Column(name = "client_revision", nullable = false)
    private long clientRevision;

    // ---- Audit columns (house Auditable pattern) ---------------------------

    @Column(name = "created_at", nullable = false, updatable = false)
    @JsonProperty(access = JsonProperty.Access.READ_ONLY)
    private LocalDateTime createdAt;

    /** UTC; doubles as the presence signal (touched within ~60 s = in the room). */
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
    }
}
