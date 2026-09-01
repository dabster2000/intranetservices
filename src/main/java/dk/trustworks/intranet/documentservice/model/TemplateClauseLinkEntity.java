package dk.trustworks.intranet.documentservice.model;

import io.quarkus.hibernate.orm.panache.PanacheEntityBase;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.PrePersist;
import jakarta.persistence.Table;
import jakarta.validation.constraints.NotBlank;
import lombok.Data;
import lombok.EqualsAndHashCode;

import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;

/**
 * One clause offered on one template (template-clauses spec §4.4).
 * {@code required} links are always included in the bundle — the preparer
 * cannot deselect them; {@code preselected} links start ticked.
 */
@Data
@EqualsAndHashCode(onlyExplicitlyIncluded = true, callSuper = false)
@Entity
@Table(name = "template_clause_links")
public class TemplateClauseLinkEntity extends PanacheEntityBase {

    @Id
    @EqualsAndHashCode.Include
    @Column(length = 36)
    private String uuid;

    @Column(name = "template_uuid", nullable = false, length = 36)
    @NotBlank(message = "Template is required")
    private String templateUuid;

    @Column(name = "clause_uuid", nullable = false, length = 36)
    @NotBlank(message = "Clause is required")
    private String clauseUuid;

    @Column(nullable = false)
    private boolean preselected = false;

    @Column(nullable = false)
    private boolean required = false;

    @Column(name = "display_order", nullable = false)
    private int displayOrder = 0;

    @Column(name = "created_at", nullable = false, updatable = false)
    private LocalDateTime createdAt;

    @PrePersist
    protected void onCreate() {
        if (uuid == null) {
            uuid = UUID.randomUUID().toString();
        }
        if (createdAt == null) {
            createdAt = LocalDateTime.now();
        }
    }

    // --- Panache finder methods ---

    public static List<TemplateClauseLinkEntity> findByTemplate(String templateUuid) {
        return list("templateUuid = ?1 ORDER BY displayOrder", templateUuid);
    }

    public static List<TemplateClauseLinkEntity> findByClause(String clauseUuid) {
        return list("clauseUuid = ?1", clauseUuid);
    }

    public static long deleteByTemplate(String templateUuid) {
        return delete("templateUuid = ?1", templateUuid);
    }
}
