package dk.trustworks.intranet.documentservice.model;

import dk.trustworks.intranet.documentservice.model.enums.EmployeeDocumentAuditAction;
import io.quarkus.hibernate.orm.panache.PanacheEntityBase;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;
import java.util.List;

/**
 * GDPR art. 30 access/processing trail for the employee document store
 * (spec §6.2, V452). One row per upload/download/mutation; user-level
 * actions (ERASE_ALL, DSAR_EXPORT, retention run summaries) have a null
 * {@code documentUuid}. On erasure the {@code detail} column is scrubbed
 * — the fact of processing is retained, the content PII is not.
 */
@Data
@NoArgsConstructor
@Entity
@Table(name = "employee_document_audit")
public class EmployeeDocumentAudit extends PanacheEntityBase {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "document_uuid", length = 36)
    private String documentUuid;

    /** Whose file was touched. */
    @Column(name = "user_uuid", nullable = false, length = 36)
    private String userUuid;

    /** Who did it; null = system/batch. */
    @Column(name = "actor_uuid", length = 36)
    private String actorUuid;

    @Enumerated(EnumType.STRING)
    @Column(name = "action", nullable = false)
    private EmployeeDocumentAuditAction action;

    /** Filename/category at time of action; scrubbed on erasure. */
    @Column(name = "detail", length = 1024)
    private String detail;

    @Column(name = "created_at", nullable = false, insertable = false, updatable = false)
    private LocalDateTime createdAt;

    public EmployeeDocumentAudit(String documentUuid, String userUuid, String actorUuid,
                                 EmployeeDocumentAuditAction action, String detail) {
        this.documentUuid = documentUuid;
        this.userUuid = userUuid;
        this.actorUuid = actorUuid;
        this.action = action;
        this.detail = detail == null ? null : (detail.length() > 1024 ? detail.substring(0, 1024) : detail);
    }

    public static List<EmployeeDocumentAudit> findByUser(String userUuid) {
        return list("userUuid = ?1 ORDER BY createdAt ASC", userUuid);
    }
}
