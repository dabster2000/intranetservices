package dk.trustworks.intranet.recruitmentservice.model;

import dk.trustworks.intranet.recruitmentservice.model.enums.CandidateStatus;
import dk.trustworks.intranet.recruitmentservice.model.enums.SharePointMoveStatus;
import dk.trustworks.intranet.recruitmentservice.model.exception.BusinessRuleViolation;
import io.quarkus.hibernate.orm.panache.PanacheEntityBase;
import jakarta.persistence.Column;
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

    @Column(name = "email", length = 255, nullable = false)
    private String email;

    @Column(name = "phone", length = 50)
    private String phone;

    /** Soft FK to {@code companies.uuid}. */
    @Column(name = "target_company_uuid", length = 36, nullable = false)
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

    /** Soft FK to {@code users.uuid}. Set when the candidate is hired and a user record is created. */
    @Column(name = "converted_user_uuid", length = 36)
    private String convertedUserUuid;

    @Column(name = "sharepoint_folder_path", length = 512)
    private String sharepointFolderPath;

    @Enumerated(EnumType.STRING)
    @Column(name = "sharepoint_move_status", length = 20)
    private SharePointMoveStatus sharepointMoveStatus;

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
     * @return true iff this candidate is in a terminal state (HIRED, DECLINED
     *         or WITHDRAWN). Useful for the application service when deciding
     *         whether to also call {@code CandidateDossier.closeOnTerminal()}.
     */
    public boolean isTerminal() {
        return status != CandidateStatus.ACTIVE;
    }

    private void guardActive(String operation) {
        if (status != CandidateStatus.ACTIVE) {
            throw new BusinessRuleViolation(
                    "Cannot %s candidate %s: status is %s, expected ACTIVE"
                            .formatted(operation, uuid, status));
        }
    }
}
