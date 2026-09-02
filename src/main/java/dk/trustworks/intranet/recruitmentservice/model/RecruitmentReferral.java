package dk.trustworks.intranet.recruitmentservice.model;

import com.fasterxml.jackson.annotation.JsonProperty;
import dk.trustworks.intranet.model.Auditable;
import dk.trustworks.intranet.recruitmentservice.model.enums.RecruitmentReferralClosedReason;
import dk.trustworks.intranet.recruitmentservice.model.enums.RecruitmentReferralRelation;
import dk.trustworks.intranet.recruitmentservice.model.enums.RecruitmentReferralStatus;
import dk.trustworks.intranet.recruitmentservice.model.exception.BusinessRuleViolation;
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
import jakarta.persistence.Version;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.Objects;
import java.util.UUID;

/**
 * An employee referral — the pre-candidate intake record of the referral
 * channel (ATS spec §4.1/§5.2). Submitted by any employee; a recruiter
 * later triages it into a {@link RecruitmentCandidate} (with an optional
 * immediate position attach) or dismisses it. The row is the durable link
 * between referrer and candidate that "My referrals" and the P12 referrer
 * notifications key on.
 * <p>
 * Status semantics (plan §P6, locked here + in tests):
 * <ul>
 *   <li>{@code SUBMITTED} — awaiting triage; the only state the triage
 *       transitions accept;</li>
 *   <li>{@code TRIAGED} — candidate created, no position attach at triage;</li>
 *   <li>{@code CONVERTED} — candidate created AND application attached at
 *       triage;</li>
 *   <li>{@code CLOSED} — dismissed at triage ({@link #closedReason} set).</li>
 * </ul>
 * The candidate's later pipeline journey is deliberately NOT mirrored back
 * onto this row — the referrer-facing status derives live from
 * candidate/application state on every read.
 * <p>
 * State changes are only made through {@code ReferralService}, which pairs
 * every mutation with its {@code REFERRAL_*} event on the recruitment
 * event stream.
 */
@Getter
@Setter
@NoArgsConstructor
@Entity
@Table(name = "recruitment_referrals")
@EntityListeners(AuditEntityListener.class)
public class RecruitmentReferral extends PanacheEntityBase implements Auditable {

    @Id
    @Column(name = "uuid", length = 36, nullable = false, updatable = false)
    private String uuid;

    /** Soft FK to {@code users.uuid} — the submitting employee (X-Requested-By). */
    @Column(name = "referrer_uuid", length = 36, nullable = false, updatable = false)
    private String referrerUuid;

    @Enumerated(EnumType.STRING)
    @Column(name = "referrer_relation", length = 20, nullable = false)
    private RecruitmentReferralRelation referrerRelation;

    /** PII. Set when the real reference is not the submitting employee. */
    @Column(name = "external_referrer_name", length = 200)
    private String externalReferrerName;

    /** PII. Full name as submitted — the recruiter splits it at triage. */
    @Column(name = "candidate_name", length = 200, nullable = false)
    private String candidateName;

    /** PII. Optional profile link. */
    @Column(name = "linkedin_url", length = 500)
    private String linkedinUrl;

    /** PII. Optional — aids dedupe at triage. */
    @Column(name = "email", length = 255)
    private String email;

    /** PII. The referrer's context/why (max 2000 chars, service-enforced). */
    @Column(name = "why_text", columnDefinition = "TEXT", nullable = false)
    private String whyText;

    /**
     * Soft FK to {@code files.uuid} — the optional CV the referrer
     * attached. The S3 object's {@code relateduuid} is THIS referral
     * while the referral is pre-candidate; the triage create leg
     * re-points it at the new candidate, the dismiss leg deletes it.
     */
    @Column(name = "cv_file_uuid", length = 36)
    private String cvFileUuid;

    /** PII. Sanitised original filename of the attached CV. */
    @Column(name = "cv_filename", length = 255)
    private String cvFilename;

    /** Stored MIME type of the attached CV. */
    @Column(name = "cv_content_type", length = 100)
    private String cvContentType;

    /** Size in bytes of the attached CV. */
    @Column(name = "cv_size_bytes")
    private Integer cvSizeBytes;

    /** UTC. When the CV was attached. */
    @Column(name = "cv_uploaded_at")
    @JsonProperty(access = JsonProperty.Access.READ_ONLY)
    private LocalDateTime cvUploadedAt;

    /** FK to {@code recruitment_candidates.uuid} — set by the triage create leg. */
    @Column(name = "candidate_uuid", length = 36)
    private String candidateUuid;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", length = 16, nullable = false)
    private RecruitmentReferralStatus status = RecruitmentReferralStatus.SUBMITTED;

    /** Mandatory on the DISMISS leg; {@code NULL} otherwise. */
    @Enumerated(EnumType.STRING)
    @Column(name = "closed_reason", length = 32)
    private RecruitmentReferralClosedReason closedReason;

    /** UTC. Set at submission. */
    @Column(name = "submitted_at", nullable = false, updatable = false)
    @JsonProperty(access = JsonProperty.Access.READ_ONLY)
    private LocalDateTime submittedAt;

    /** UTC. Set when a recruiter triages (create or dismiss). */
    @Column(name = "triaged_at")
    @JsonProperty(access = JsonProperty.Access.READ_ONLY)
    private LocalDateTime triagedAt;

    /** Soft FK to {@code users.uuid} — the recruiter who triaged. */
    @Column(name = "triaged_by_useruuid", length = 36)
    private String triagedByUseruuid;

    /**
     * Optimistic lock guarding the one-shot triage: two concurrent triage
     * transactions both pass the {@link #guardSubmitted} read, but the
     * loser's versioned UPDATE matches zero rows and its whole transaction
     * (candidate creation included) rolls back — {@code ReferralService}
     * flushes synchronously and surfaces it as the same 409. Boxed
     * {@code Long}, never {@code long} (house rule — a primitive zero is
     * indistinguishable from "no version" and breaks merge semantics).
     */
    @Version
    @Column(name = "version", nullable = false)
    private Long version;

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

    // ---- Lifecycle callbacks -----------------------------------------------

    @PrePersist
    protected void onCreate() {
        if (uuid == null) {
            uuid = UUID.randomUUID().toString();
        }
        if (status == null) {
            status = RecruitmentReferralStatus.SUBMITTED;
        }
        if (submittedAt == null) {
            submittedAt = LocalDateTime.now(ZoneOffset.UTC);
        }
    }

    // ---- Domain methods (rich aggregate, not anemic) -----------------------

    /**
     * Record the triage outcome "candidate created": link the candidate and
     * move to {@link RecruitmentReferralStatus#TRIAGED} (or
     * {@link RecruitmentReferralStatus#CONVERTED} when an application was
     * attached in the same triage). Stamps {@link #triagedAt}/
     * {@link #triagedByUseruuid}.
     *
     * @param candidateUuid      the freshly created candidate's uuid
     * @param attachedToPosition whether triage also attached an application
     * @param actor              the triaging recruiter
     * @throws BusinessRuleViolation if the referral is not SUBMITTED —
     *         triage is one-shot (idempotency for the P14 Slack actions)
     */
    public void triageToCandidate(String candidateUuid, boolean attachedToPosition, UUID actor) {
        Objects.requireNonNull(candidateUuid, "candidateUuid must not be null");
        Objects.requireNonNull(actor, "actor must not be null");
        guardSubmitted("triage");
        this.candidateUuid = candidateUuid;
        this.status = attachedToPosition
                ? RecruitmentReferralStatus.CONVERTED
                : RecruitmentReferralStatus.TRIAGED;
        stampTriage(actor);
    }

    /**
     * Record the triage outcome "dismissed": {@link RecruitmentReferralStatus#CLOSED}
     * with a mandatory coded reason. No candidate is ever created on this leg.
     *
     * @throws BusinessRuleViolation if the referral is not SUBMITTED
     */
    public void dismiss(RecruitmentReferralClosedReason reason, UUID actor) {
        Objects.requireNonNull(reason, "reason must not be null");
        Objects.requireNonNull(actor, "actor must not be null");
        guardSubmitted("dismiss");
        this.status = RecruitmentReferralStatus.CLOSED;
        this.closedReason = reason;
        stampTriage(actor);
    }

    /**
     * Record the optional CV the referrer attached. Only a referral still
     * awaiting triage accepts one — once a recruiter has decided, the
     * file belongs on the candidate (or nowhere), never back here.
     *
     * @return the {@code fileUuid} that was previously attached and must
     *         now be deleted from S3, or {@code null} when this is the
     *         first attachment (re-attaching replaces, it does not
     *         accumulate — the referral carries at most one CV)
     * @throws BusinessRuleViolation if the referral is not SUBMITTED
     */
    public String attachCv(String fileUuid, String filename, String contentType, int sizeBytes) {
        Objects.requireNonNull(fileUuid, "fileUuid must not be null");
        Objects.requireNonNull(filename, "filename must not be null");
        guardSubmitted("attach a CV to");
        String replaced = this.cvFileUuid;
        this.cvFileUuid = fileUuid;
        this.cvFilename = filename;
        this.cvContentType = contentType;
        this.cvSizeBytes = sizeBytes;
        this.cvUploadedAt = LocalDateTime.now(ZoneOffset.UTC);
        return replaced;
    }

    /** Whether a CV is attached. */
    public boolean hasCv() {
        return cvFileUuid != null && !cvFileUuid.isBlank();
    }

    /**
     * Forget the attached CV — the caller is responsible for deleting the
     * S3 object it points at. Used by the dismiss leg, where no candidate
     * will ever exist to own the file.
     */
    public void clearCv() {
        this.cvFileUuid = null;
        this.cvFilename = null;
        this.cvContentType = null;
        this.cvSizeBytes = null;
        this.cvUploadedAt = null;
    }

    private void stampTriage(UUID actor) {
        this.triagedAt = LocalDateTime.now(ZoneOffset.UTC);
        this.triagedByUseruuid = actor.toString();
    }

    private void guardSubmitted(String operation) {
        if (status != RecruitmentReferralStatus.SUBMITTED) {
            throw new BusinessRuleViolation(
                    "Cannot %s referral %s: status is %s, expected SUBMITTED — a referral is triaged exactly once"
                            .formatted(operation, uuid, status));
        }
    }
}
