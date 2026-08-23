package dk.trustworks.intranet.hrletters.model;

import dk.trustworks.intranet.hrletters.model.enums.HrLetterStatus;
import dk.trustworks.intranet.hrletters.model.enums.HrLetterType;
import io.quarkus.hibernate.orm.panache.PanacheEntityBase;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.PrePersist;
import jakarta.persistence.PreUpdate;
import jakarta.persistence.Table;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import lombok.Data;
import lombok.EqualsAndHashCode;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * One HR letter: a salary-regulation notice draft/delivery or a
 * vacation-transfer request/agreement. See {@code V528__Hr_letters.sql}
 * for the flow description and the legal basis.
 *
 * <p>{@code payload} is a JSON string with the type-specific facts —
 * salary: {@code oldSalary, newSalary, adjustment, effectiveDate,
 * salaryType, previousType}; vacation: {@code days, fromYear, toYear}
 * (years are the September start-years of the vacation years).</p>
 */
@Data
@EqualsAndHashCode(onlyExplicitlyIncluded = true, callSuper = false)
@Entity
@Table(name = "hr_letters")
public class HrLetter extends PanacheEntityBase {

    @Id
    @EqualsAndHashCode.Include
    @Column(length = 36)
    private String uuid;

    @Column(nullable = false, length = 36)
    @NotBlank
    private String useruuid;

    @Column(name = "letter_type", nullable = false, length = 32)
    @Enumerated(EnumType.STRING)
    @NotNull
    private HrLetterType letterType;

    @Column(nullable = false, length = 16)
    @Enumerated(EnumType.STRING)
    @NotNull
    private HrLetterStatus status = HrLetterStatus.DRAFT;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(nullable = false, columnDefinition = "JSON")
    private String payload;

    @Column(name = "salary_uuid", length = 36)
    private String salaryUuid;

    @Column(name = "template_uuid", length = 36)
    private String templateUuid;

    @Column(name = "employee_document_uuid", length = 36)
    private String employeeDocumentUuid;

    @Column(name = "requested_by", nullable = false, length = 36)
    private String requestedBy;

    @Column(name = "approved_by", length = 36)
    private String approvedBy;

    @Column(name = "dismissed_by", length = 36)
    private String dismissedBy;

    @Column(name = "dismiss_reason", length = 500)
    private String dismissReason;

    @Column(name = "sent_at")
    private LocalDateTime sentAt;

    @Column(name = "acknowledged_at")
    private LocalDateTime acknowledgedAt;

    @Column(name = "created_at", nullable = false, updatable = false)
    private LocalDateTime createdAt;

    @Column(name = "updated_at", nullable = false)
    private LocalDateTime updatedAt;

    @PrePersist
    protected void onCreate() {
        if (uuid == null) {
            uuid = UUID.randomUUID().toString();
        }
        createdAt = LocalDateTime.now();
        updatedAt = LocalDateTime.now();
    }

    @PreUpdate
    protected void onUpdate() {
        updatedAt = LocalDateTime.now();
    }

    // --- Panache finders ---

    public static List<HrLetter> findForUser(String useruuid) {
        return list("useruuid = ?1 ORDER BY createdAt DESC", useruuid);
    }

    public static Optional<HrLetter> findDraftBySalaryUuid(String salaryUuid) {
        return find("salaryUuid = ?1 AND status = ?2", salaryUuid, HrLetterStatus.DRAFT)
                .firstResultOptional();
    }

    public static Optional<HrLetter> findDraftVacationRequest(String useruuid) {
        return find("useruuid = ?1 AND letterType = ?2 AND status = ?3",
                useruuid, HrLetterType.VACATION_TRANSFER, HrLetterStatus.DRAFT)
                .firstResultOptional();
    }
}
