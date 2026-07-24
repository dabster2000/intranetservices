package dk.trustworks.intranet.recruitmentservice.model;

import dk.trustworks.intranet.recruitmentservice.model.enums.CandidateEducationLevel;
import dk.trustworks.intranet.recruitmentservice.model.enums.CandidateExperienceLevel;
import dk.trustworks.intranet.recruitmentservice.model.enums.CandidateLawfulBasis;
import dk.trustworks.intranet.recruitmentservice.model.enums.CandidatePoolStatus;
import dk.trustworks.intranet.recruitmentservice.model.enums.CandidateSecurityClearance;
import dk.trustworks.intranet.recruitmentservice.model.enums.CandidateSource;
import dk.trustworks.intranet.recruitmentservice.model.enums.CandidateStatus;
import dk.trustworks.intranet.recruitmentservice.model.enums.SharePointMoveStatus;
import dk.trustworks.intranet.recruitmentservice.model.exception.BusinessRuleViolation;
import io.quarkus.hibernate.orm.panache.PanacheEntityBase;
import jakarta.persistence.Column;
import jakarta.persistence.Convert;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.PrePersist;
import jakarta.persistence.PreUpdate;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;

/**
 * Aggregate root for the recruitment process.
 * <p>
 * Owns the candidate's lifecycle: a candidate is born {@link CandidateStatus#ACTIVE}
 * and transitions exactly once into a terminal state via one of the
 * {@link #decline(String, UUID)}, {@link #withdraw(String, UUID)}, or
 * {@link #markHired(UUID, UUID)} domain methods.
 * <p>
 * The aggregate also owns child {@code CandidateDossier}s, but those are
 * accessed through their own repository — this entity does not declare a
 * collection mapping (each dossier is an aggregate root in its own right
 * for write purposes; the candidate UUID is the soft FK that ties them
 * together). Cross-aggregate references such as
 * {@link #targetCompanyUuid}, {@link #convertedUserUuid} and
 * {@link #createdByUseruuid} are stored as plain UUID strings — there is
 * no JPA association.
 */
@Getter
@Setter
@NoArgsConstructor
@Entity
@Table(name = "recruitment_candidates")
public class RecruitmentCandidate extends PanacheEntityBase {

    @Id
    @Column(name = "uuid", length = 36, nullable = false, updatable = false)
    private String uuid;

    @Column(name = "first_name", length = 100, nullable = false)
    private String firstName;

    @Column(name = "last_name", length = 100, nullable = false)
    private String lastName;

    /** Nullable since V435 — LinkedIn imports and pool candidates may lack one. */
    @Column(name = "email", length = 255)
    private String email;

    @Column(name = "phone", length = 50)
    private String phone;

    /** PII. Pasted LinkedIn profile URL; dedupe compares normalized /in/ slugs. */
    @Column(name = "linkedin_url", length = 500)
    private String linkedinUrl;

    /** Soft FK to {@code companies.uuid}. Nullable since V435 (talent-pool candidates). */
    @Column(name = "target_company_uuid", length = 36)
    private String targetCompanyUuid;

    @Column(name = "target_start_date")
    private LocalDate targetStartDate;

    @Column(name = "notes", columnDefinition = "TEXT")
    private String notes;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", length = 20, nullable = false)
    private CandidateStatus status = CandidateStatus.ACTIVE;

    @Column(name = "decline_reason", columnDefinition = "TEXT")
    private String declineReason;

    // ---- ATS: sourcing & referral (plan §P3, spec §4.1) -----------------------

    @Enumerated(EnumType.STRING)
    @Column(name = "source", length = 20)
    private CandidateSource source;

    /** Structured sub-source; PII inside (reference names). Shape depends on {@link #source}. */
    @Convert(converter = JsonMapConverter.class)
    @Column(name = "source_detail", columnDefinition = "JSON")
    private Map<String, Object> sourceDetail;

    /** Soft FK to {@code users.uuid} — internal referrer. */
    @Column(name = "referred_by_user_uuid", length = 36)
    private String referredByUserUuid;

    /** PII. Non-employee reference name. */
    @Column(name = "external_referrer_name", length = 200)
    private String externalReferrerName;

    /** Soft FK to {@code users.uuid} — partner-referral mandate. */
    @Column(name = "sponsoring_partner_uuid", length = 36)
    private String sponsoringPartnerUuid;

    /** Soft FK to {@code users.uuid} — triage routing for pool candidates. */
    @Column(name = "relevant_teamlead_uuid", length = 36)
    private String relevantTeamleadUuid;

    // ---- ATS: pool & qualifications --------------------------------------------

    /** Only meaningful while {@link #status} is {@code POOLED}; cleared on unpool. */
    @Enumerated(EnumType.STRING)
    @Column(name = "pool_status", length = 20)
    private CandidatePoolStatus poolStatus;

    @Convert(converter = StringListConverter.class)
    @Column(name = "tags", columnDefinition = "JSON")
    private List<String> tags;

    @Enumerated(EnumType.STRING)
    @Column(name = "education_level", length = 20)
    private CandidateEducationLevel educationLevel;

    @Column(name = "education_other", length = 200)
    private String educationOther;

    @Enumerated(EnumType.STRING)
    @Column(name = "experience_level", length = 20)
    private CandidateExperienceLevel experienceLevel;

    /** Practice-scoped role tags from the per-practice catalog in settings. */
    @Convert(converter = StringListConverter.class)
    @Column(name = "specializations", columnDefinition = "JSON")
    private List<String> specializations;

    @Enumerated(EnumType.STRING)
    @Column(name = "security_clearance", length = 10)
    private CandidateSecurityClearance securityClearance;

    /** Candidate open to clearance-required work. */
    @Column(name = "security_relevant")
    private Boolean securityRelevant;

    /**
     * Spoken/written languages — free-form JSON string array like
     * {@link #tags} (V440, AI-suggestible via the P9 intake chips).
     */
    @Convert(converter = StringListConverter.class)
    @Column(name = "languages", columnDefinition = "TEXT")
    private List<String> languages;

    /**
     * Current employer (free text, V440, AI-suggestible). Classified as
     * personal data on events — {@code current_employer} is a forbidden
     * payload key; changes travel in the pii section.
     */
    @Column(name = "current_employer", length = 200)
    private String currentEmployer;

    // ---- ATS: GDPR bookkeeping (data only until P19) ----------------------------

    /** System-maintained — never accepted from API clients. */
    @Enumerated(EnumType.STRING)
    @Column(name = "lawful_basis", length = 30)
    private CandidateLawfulBasis lawfulBasis;

    @Column(name = "retention_deadline")
    private LocalDateTime retentionDeadline;

    @Column(name = "art14_required")
    private Boolean art14Required;

    @Column(name = "art14_deadline")
    private LocalDateTime art14Deadline;

    @Column(name = "process_ended_at")
    private LocalDateTime processEndedAt;

    @Column(name = "anonymized_at")
    private LocalDateTime anonymizedAt;

    /** Soft FK to {@code users.uuid}. Set when the candidate is hired and a user record is created. */
    @Column(name = "converted_user_uuid", length = 36)
    private String convertedUserUuid;

    @Column(name = "sharepoint_folder_path", length = 512)
    private String sharepointFolderPath;

    @Enumerated(EnumType.STRING)
    @Column(name = "sharepoint_move_status", length = 20)
    private SharePointMoveStatus sharepointMoveStatus;

    /**
     * S3→S3 promotion state (employee-documents spec §6.5.3, V454).
     * Set to PENDING instead of {@code sharepoint_move_status} when the
     * {@code employee_documents.writers.promotion} toggle is ON at
     * conversion time; re-driven to COMPLETED/FAILED by the
     * nextsign-status-sync sweep. NULL = legacy SharePoint pipeline.
     */
    @Enumerated(EnumType.STRING)
    @Column(name = "promotion_status", length = 20)
    private dk.trustworks.intranet.recruitmentservice.model.enums.PromotionStatus promotionStatus;

    /** Soft FK to {@code users.uuid}. Recruiter or manager who created the candidate. */
    @Column(name = "created_by_useruuid", length = 36, nullable = false)
    private String createdByUseruuid;

    @Column(name = "created_at", nullable = false, updatable = false)
    private LocalDateTime createdAt;

    @Column(name = "updated_at", nullable = false)
    private LocalDateTime updatedAt;

    // ---- Lifecycle callbacks --------------------------------------------------

    @PrePersist
    protected void onCreate() {
        if (uuid == null) {
            uuid = UUID.randomUUID().toString();
        }
        LocalDateTime now = LocalDateTime.now();
        if (createdAt == null) {
            createdAt = now;
        }
        updatedAt = now;
        if (status == null) {
            status = CandidateStatus.ACTIVE;
        }
    }

    @PreUpdate
    protected void onUpdate() {
        updatedAt = LocalDateTime.now();
    }

    // ---- Domain methods (rich aggregate, not anemic) -------------------------

    /**
     * Decline a candidate that is currently {@link CandidateStatus#ACTIVE}.
     * Sets {@link #status} to {@link CandidateStatus#DECLINED} and records the
     * decline reason. Mutates this aggregate only — caller is responsible for
     * closing any open dossiers and persisting the change.
     *
     * @param reason free-text reason; stored verbatim (may be {@code null})
     * @param actor  the user performing the decline (recorded in audit log
     *               by the application layer)
     * @throws BusinessRuleViolation if the candidate is not currently ACTIVE
     */
    public void decline(String reason, UUID actor) {
        Objects.requireNonNull(actor, "actor must not be null");
        guardActive("decline");
        this.status = CandidateStatus.DECLINED;
        this.declineReason = reason;
    }

    /**
     * Mark a candidate that is currently {@link CandidateStatus#ACTIVE} as
     * having withdrawn (i.e. the candidate themselves backed out). Same
     * mechanics as {@link #decline(String, UUID)} but produces a different
     * terminal state for reporting.
     *
     * @throws BusinessRuleViolation if the candidate is not currently ACTIVE
     */
    public void withdraw(String reason, UUID actor) {
        Objects.requireNonNull(actor, "actor must not be null");
        guardActive("withdraw");
        this.status = CandidateStatus.WITHDRAWN;
        this.declineReason = reason;
    }

    /**
     * Promote a currently {@link CandidateStatus#ACTIVE} candidate to
     * {@link CandidateStatus#HIRED} and link them to the newly created user
     * account.
     *
     * @param newUserUuid UUID of the newly provisioned {@code users} row
     * @param actor       the user performing the hire
     * @throws BusinessRuleViolation if the candidate is not currently ACTIVE
     */
    public void markHired(UUID newUserUuid, UUID actor) {
        Objects.requireNonNull(newUserUuid, "newUserUuid must not be null");
        Objects.requireNonNull(actor, "actor must not be null");
        guardActive("markHired");
        this.status = CandidateStatus.HIRED;
        this.convertedUserUuid = newUserUuid.toString();
    }

    /**
     * Move an {@link CandidateStatus#ACTIVE} candidate into the talent pool,
     * or re-bucket an already {@link CandidateStatus#POOLED} one. Not a
     * terminal transition — {@link #unpool(UUID)} reverses it.
     *
     * @param poolStatus pool bucket; defaults to {@link CandidatePoolStatus#PROSPECT}
     *                   when {@code null}
     * @throws BusinessRuleViolation if the candidate is in a terminal state
     */
    public void pool(CandidatePoolStatus poolStatus, UUID actor) {
        Objects.requireNonNull(actor, "actor must not be null");
        if (status != CandidateStatus.ACTIVE && status != CandidateStatus.POOLED) {
            throw new BusinessRuleViolation(
                    "Cannot pool candidate %s: status is %s, expected ACTIVE or POOLED"
                            .formatted(uuid, status));
        }
        this.status = CandidateStatus.POOLED;
        this.poolStatus = poolStatus != null ? poolStatus : CandidatePoolStatus.PROSPECT;
    }

    /**
     * Bring a {@link CandidateStatus#POOLED} candidate back to
     * {@link CandidateStatus#ACTIVE} (e.g. before attaching them to a
     * position in P4). Clears {@link #poolStatus}.
     *
     * @throws BusinessRuleViolation if the candidate is not currently POOLED
     */
    public void unpool(UUID actor) {
        Objects.requireNonNull(actor, "actor must not be null");
        if (status != CandidateStatus.POOLED) {
            throw new BusinessRuleViolation(
                    "Cannot unpool candidate %s: status is %s, expected POOLED"
                            .formatted(uuid, status));
        }
        this.status = CandidateStatus.ACTIVE;
        this.poolStatus = null;
    }

    /**
     * @return true iff this candidate is in a terminal state (HIRED, DECLINED,
     *         WITHDRAWN or ANONYMIZED). POOLED is NOT terminal — pool
     *         candidates re-enter the funnel. Useful for the application
     *         service when deciding whether to also call
     *         {@code CandidateDossier.closeOnTerminal()}.
     */
    public boolean isTerminal() {
        return status != CandidateStatus.ACTIVE && status != CandidateStatus.POOLED;
    }

    /**
     * GDPR anonymization of THIS row (ATS P19, spec §5.5): every column
     * that can carry personal data is nulled or replaced with a fixed
     * placeholder; the structural skeleton (source enum, education /
     * experience levels, specialization codes, clearance, dates, soft FKs
     * to employees) survives so statistics keep working. Irreversible by
     * design. Only {@code RecruitmentAnonymizerService} calls this — the
     * row scrub is one step of the four-target anonymization contract
     * (events pii, answers, S3 documents are the service's other legs).
     * <p>
     * The caller guards state: HIRED candidates leave the recruitment
     * retention regime (spec §5.5) and an already-ANONYMIZED candidate is
     * a no-op at the service layer.
     */
    public void anonymize() {
        this.firstName = "Anonymized";
        this.lastName = "Candidate";
        this.email = null;
        this.phone = null;
        this.linkedinUrl = null;
        this.notes = null;
        this.declineReason = null;
        this.sourceDetail = null;          // reference names live inside
        this.externalReferrerName = null;
        this.tags = null;                  // free-form text
        this.educationOther = null;        // free-form text
        this.currentEmployer = null;
        this.languages = null;             // free-form text (AI chips)
        this.sharepointFolderPath = null;  // contains the candidate's name
        this.poolStatus = null;
        this.retentionDeadline = null;     // the clock has been consumed
        this.status = CandidateStatus.ANONYMIZED;
        this.anonymizedAt = LocalDateTime.now(java.time.ZoneOffset.UTC);
    }

    private void guardActive(String operation) {
        if (status != CandidateStatus.ACTIVE) {
            throw new BusinessRuleViolation(
                    "Cannot %s candidate %s: status is %s, expected ACTIVE"
                            .formatted(operation, uuid, status));
        }
    }
}
