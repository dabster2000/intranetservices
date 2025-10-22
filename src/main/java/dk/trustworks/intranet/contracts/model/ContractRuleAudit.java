package dk.trustworks.intranet.contracts.model;

import io.quarkus.hibernate.orm.panache.PanacheEntityBase;
import jakarta.persistence.*;
import lombok.Data;
import lombok.EqualsAndHashCode;

import java.time.LocalDateTime;
import java.util.List;

/**
 * JPA Entity for querying contract rule audit logs.
 * This table is automatically populated by database triggers for INSERT/UPDATE/DELETE
 * operations on override tables.
 *
 * <p><b>IMPORTANT</b>: This entity is READ-ONLY. Do not attempt to persist or modify
 * audit records through this entity. All audit entries are created by database triggers.
 *
 * <p>Use cases:
 * <ul>
 *   <li>Query audit history for specific contracts</li>
 *   <li>Track who created/modified/deleted overrides and when</li>
 *   <li>Compliance and security auditing</li>
 * </ul>
 *
 * <p>Example usage:
 * <pre>
 * // Find all audit entries for a specific contract
 * List<ContractRuleAudit> auditLog =
 *     ContractRuleAudit.findByContractOrderByDate("contract-uuid-123");
 *
 * // Find recent changes by a specific user
 * List<ContractRuleAudit> userActions =
 *     ContractRuleAudit.findByModifiedBy("admin@trustworks.dk");
 * </pre>
 */
@Entity
@Table(
    name = "contract_rule_audit",
    indexes = {
        @Index(name = "idx_cra_contract", columnList = "contract_uuid"),
        @Index(name = "idx_cra_table_entity", columnList = "table_name, entity_id"),
        @Index(name = "idx_cra_modified_at", columnList = "modified_at"),
        @Index(name = "idx_cra_modified_by", columnList = "modified_by")
    }
)
@Data
@EqualsAndHashCode(onlyExplicitlyIncluded = true, callSuper = false)
public class ContractRuleAudit extends PanacheEntityBase {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @EqualsAndHashCode.Include
    private Long id;

    /**
     * The contract UUID this audit entry relates to.
     */
    @Column(name = "contract_uuid", nullable = false, length = 36)
    private String contractUuid;

    /**
     * Source table name (e.g., "contract_validation_overrides").
     */
    @Column(name = "table_name", nullable = false, length = 100)
    private String tableName;

    /**
     * ID of the entity that was modified in the source table.
     */
    @Column(name = "entity_id", nullable = false)
    private Integer entityId;

    /**
     * The rule ID being audited (for quick filtering).
     */
    @Column(name = "rule_id", length = 64)
    private String ruleId;

    /**
     * Type of operation: INSERT, UPDATE, or DELETE.
     */
    @Column(nullable = false, length = 10)
    private String operation;

    /**
     * JSON snapshot of the old state (NULL for INSERT).
     */
    @Column(name = "old_values", columnDefinition = "JSON")
    private String oldValues;

    /**
     * JSON snapshot of the new state (NULL for DELETE).
     */
    @Column(name = "new_values", columnDefinition = "JSON")
    private String newValues;

    /**
     * Email of the user who performed the modification.
     */
    @Column(name = "modified_by", length = 100)
    private String modifiedBy;

    /**
     * Timestamp when the modification occurred.
     */
    @Column(name = "modified_at", nullable = false, updatable = false)
    private LocalDateTime modifiedAt;

    // --- Panache finder methods ---

    /**
     * Find all audit entries for a specific contract, ordered by date (newest first).
     *
     * @param contractUuid The contract UUID
     * @return List of audit entries
     */
    public static List<ContractRuleAudit> findByContractOrderByDate(String contractUuid) {
        return find("contractUuid = ?1 ORDER BY modifiedAt DESC", contractUuid).list();
    }

    /**
     * Find audit entries for a specific contract and rule.
     *
     * @param contractUuid The contract UUID
     * @param ruleId The rule ID
     * @return List of audit entries, ordered by date (newest first)
     */
    public static List<ContractRuleAudit> findByContractAndRule(String contractUuid, String ruleId) {
        return find("contractUuid = ?1 AND ruleId = ?2 ORDER BY modifiedAt DESC",
            contractUuid, ruleId).list();
    }

    /**
     * Find recent audit entries across all contracts.
     *
     * @param limit Maximum number of entries to return
     * @return List of recent audit entries
     */
    public static List<ContractRuleAudit> findRecentEntries(int limit) {
        return find("ORDER BY modifiedAt DESC").page(0, limit).list();
    }

    /**
     * Find audit entries by user.
     *
     * @param modifiedBy Email of the user who made the modifications
     * @return List of audit entries, ordered by date (newest first)
     */
    public static List<ContractRuleAudit> findByModifiedBy(String modifiedBy) {
        return find("modifiedBy = ?1 ORDER BY modifiedAt DESC", modifiedBy).list();
    }

    /**
     * Find audit entries by operation type.
     *
     * @param operation The operation type (INSERT, UPDATE, DELETE)
     * @return List of audit entries, ordered by date (newest first)
     */
    public static List<ContractRuleAudit> findByOperation(String operation) {
        return find("operation = ?1 ORDER BY modifiedAt DESC", operation).list();
    }

    /**
     * Find audit entries by source table.
     *
     * @param tableName The source table name
     * @return List of audit entries, ordered by date (newest first)
     */
    public static List<ContractRuleAudit> findByTable(String tableName) {
        return find("tableName = ?1 ORDER BY modifiedAt DESC", tableName).list();
    }

    /**
     * Find audit entries for a specific contract within a date range.
     *
     * @param contractUuid The contract UUID
     * @param from Start of date range (inclusive)
     * @param to End of date range (exclusive)
     * @return List of audit entries within the date range
     */
    public static List<ContractRuleAudit> findByContractAndDateRange(
        String contractUuid, LocalDateTime from, LocalDateTime to) {
        return find("contractUuid = ?1 AND modifiedAt >= ?2 AND modifiedAt < ?3 ORDER BY modifiedAt DESC",
            contractUuid, from, to).list();
    }

    /**
     * Count audit entries for a specific contract.
     *
     * @param contractUuid The contract UUID
     * @return Number of audit entries
     */
    public static long countByContract(String contractUuid) {
        return count("contractUuid = ?1", contractUuid);
    }

    /**
     * Get the most recent audit entry for a specific contract and rule.
     *
     * @param contractUuid The contract UUID
     * @param ruleId The rule ID
     * @return The most recent audit entry, or null if none exists
     */
    public static ContractRuleAudit findLatestByContractAndRule(String contractUuid, String ruleId) {
        return find("contractUuid = ?1 AND ruleId = ?2 ORDER BY modifiedAt DESC",
            contractUuid, ruleId).firstResult();
    }
}
