package dk.trustworks.intranet.recruitmentservice.model;

import com.fasterxml.jackson.annotation.JsonProperty;
import dk.trustworks.intranet.model.Auditable;
import dk.trustworks.intranet.recruitmentservice.model.enums.RecruitmentEmailBodyFormat;
import dk.trustworks.intranet.recruitmentservice.model.enums.RecruitmentEmailCopyMode;
import dk.trustworks.intranet.security.AuditEntityListener;
import io.quarkus.hibernate.orm.panache.PanacheEntityBase;
import jakarta.persistence.Column;
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
import java.util.UUID;

/**
 * One Danish candidate-email template with merge fields (ATS plan §P15).
 * <p>
 * {@link #templateKey} is the stable identity AND the trigger: the reserved
 * keys ({@code ACKNOWLEDGEMENT}, {@code REJECTION_SCREENING},
 * {@code REJECTION_POST_INTERVIEW}, {@code STAGE_<stage>}) are picked up by
 * {@code CandidateMailerReactor}; any other key is a manual-send-only
 * template. Keys are never renamed once {@code EMAIL_SENT} events reference
 * them (reporting joins on the key).
 * <p>
 * {@link #body} is read according to {@link #bodyFormat}: legacy {@code PLAIN}
 * text (HTML-escaped and newline-converted at send time, as before rich text)
 * or a {@code HTML} fragment already reduced to
 * {@code RecruitmentEmailHtmlSanitizer}'s allow-list on write. Either way the
 * body is run through the send-time conversion in
 * {@code RecruitmentEmailRenderer}, so no template can carry scripts or
 * unknown markup into a mail client.
 */
@Getter
@Setter
@NoArgsConstructor
@Entity
@Table(name = "recruitment_email_templates")
@EntityListeners(AuditEntityListener.class)
public class RecruitmentEmailTemplate extends PanacheEntityBase implements Auditable {

    @Id
    @Column(name = "uuid", length = 36, nullable = false, updatable = false)
    private String uuid;

    /** Stable identity + reactor trigger; immutable after create. */
    @Column(name = "template_key", length = 60, nullable = false, updatable = false)
    private String templateKey;

    @Column(name = "name", length = 120, nullable = false)
    private String name;

    /** Danish subject line with merge fields. */
    @Column(name = "subject", length = 300, nullable = false)
    private String subject;

    /** Danish body with merge fields; interpretation governed by {@link #bodyFormat}. */
    @Column(name = "body", columnDefinition = "TEXT", nullable = false)
    private String body;

    /**
     * Whether {@link #body} is legacy plain text or a sanitized HTML fragment.
     * Every row predating rich text is {@code PLAIN} (V485) and stays that way
     * until a recruiter deliberately saves the template from the rich editor —
     * which is what keeps a rollback to the previous release harmless for
     * templates nobody has touched.
     */
    @Enumerated(EnumType.STRING)
    @Column(name = "body_format", length = 5, nullable = false)
    private RecruitmentEmailBodyFormat bodyFormat = RecruitmentEmailBodyFormat.PLAIN;

    /**
     * {@code true} = the reactor sends immediately; {@code false} =
     * review-first (queued for recruiter approval). Partner-referral
     * rejections never auto-send regardless of this value (reactor rule).
     */
    @Column(name = "auto_send", nullable = false)
    private boolean autoSend;

    /** {@code false} = trigger ignored + hidden from the compose picker. */
    @Column(name = "active", nullable = false)
    private boolean active = true;

    /**
     * CSV of {@link dk.trustworks.intranet.recruitmentservice.model.enums.RecruitmentEmailCopyRole}
     * — which internal people are copied on this template's sends. Empty
     * (the default, and what every pre-V455 template keeps) copies nobody.
     * Resolved to people at send time and visibility-filtered.
     */
    @Column(name = "copy_roles", length = 120, nullable = false)
    private String copyRoles = "";

    /**
     * Whether the copies are invisible ({@code BCC}, the default) or
     * visible to the candidate ({@code CC}).
     */
    @Enumerated(EnumType.STRING)
    @Column(name = "copy_mode", length = 3, nullable = false)
    private RecruitmentEmailCopyMode copyMode = RecruitmentEmailCopyMode.BCC;

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
    }
}
