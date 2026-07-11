package dk.trustworks.intranet.contracts.model;

import io.quarkus.hibernate.orm.panache.PanacheEntityBase;
import jakarta.persistence.*;
import lombok.Data;
import lombok.EqualsAndHashCode;

import java.time.LocalDateTime;
import java.util.List;

/**
 * JPA Entity for the framework agreement (contract type) audit trail.
 *
 * <p>Covers mutations of {@link ContractTypeDefinition}, {@link PricingRuleStepEntity}
 * and {@link ContractValidationRuleEntity}. Rows are written by
 * {@code dk.trustworks.intranet.contracts.audit.ContractTypeAuditListener} via plain JDBC
 * in the same transaction as the mutation — this entity is <b>read-only</b> from
 * application code; do not persist it through JPA.
 *
 * <p>Distinct from {@link ContractRuleAudit}, which belongs to the per-contract
 * override system and is keyed by contract uuid. This table is keyed by
 * {@code contract_type_code} (agreement level).
 *
 * @see ContractRuleAudit
 */
@Entity
@Table(name = "contract_type_audit")
@Data
@EqualsAndHashCode(onlyExplicitlyIncluded = true, callSuper = false)
public class ContractTypeAudit extends PanacheEntityBase {

    /**
     * Entity kind an audit entry refers to.
     */
    public enum EntityType {
        AGREEMENT,
        PRICING_RULE,
        VALIDATION_RULE
    }

    /**
     * Operation recorded. DELETE covers soft-disable (active true -&gt; false) and the rare
     * hard delete; RESTORE covers re-activation (active false -&gt; true).
     */
    public enum Operation {
        CREATE,
        UPDATE,
        DELETE,
        RESTORE
    }

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @EqualsAndHashCode.Include
    private Long id;

    /**
     * The agreement code ({@code contract_type_definitions.code}) the change belongs to.
     */
    @Column(name = "contract_type_code", nullable = false, length = 50)
    private String contractTypeCode;

    @Enumerated(EnumType.STRING)
    @Column(name = "entity_type", nullable = false, length = 32)
    private EntityType entityType;

    /**
     * Rule id for PRICING_RULE / VALIDATION_RULE entries; null for AGREEMENT entries.
     */
    @Column(name = "rule_id", length = 64)
    private String ruleId;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 16)
    private Operation operation;

    /**
     * User uuid from the X-Requested-By header (may be a {@code system:<client>} principal).
     * Null when the change could not be attributed.
     */
    @Column(name = "changed_by", length = 100)
    private String changedBy;

    /**
     * Set from the JVM clock at persist time so audit timestamps follow the same
     * clock/timezone as the audited entities' createdAt/updatedAt; the column's
     * DB DEFAULT CURRENT_TIMESTAMP remains only as a non-JPA fallback.
     */
    @Column(name = "changed_at", nullable = false, updatable = false)
    private LocalDateTime changedAt;

    @PrePersist
    void onPrePersist() {
        if (changedAt == null) {
            changedAt = LocalDateTime.now();
        }
    }

    /**
     * Short human-readable field diff, e.g. {@code "active: true -> false"}.
     */
    @Column(length = 1000)
    private String summary;

    // --- Panache finder methods ---

    /**
     * Find audit entries for an agreement, newest first (id is monotonic, so it is
     * the tie-safe newest-first sort key).
     *
     * @param contractTypeCode the agreement code
     * @param limit            maximum number of entries to return
     * @return newest-first list of audit entries
     */
    public static List<ContractTypeAudit> findByContractTypeCode(String contractTypeCode, int limit) {
        return find("contractTypeCode = ?1 ORDER BY id DESC", contractTypeCode)
                .page(0, limit)
                .list();
    }
}
