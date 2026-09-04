package dk.trustworks.intranet.contracts.model;

import io.quarkus.hibernate.orm.panache.PanacheEntityBase;
import jakarta.persistence.*;
import lombok.Data;
import lombok.EqualsAndHashCode;

import java.time.LocalDateTime;
import java.util.List;

/**
 * JPA Entity for the per-contract rule override audit trail ({@code contract_rule_audit},
 * created by V106 and never altered since — V107 only adds indexes).
 *
 * <p><b>This entity is READ-ONLY.</b> Nothing in the application writes to this table today:
 * V108 replaced the originally planned database triggers with an application-layer
 * {@code @EntityListeners} design (triggers need SUPER privilege when binary logging is on),
 * but that listener was never implemented. Until it is, every query here correctly returns
 * an empty result. {@code ContractTypeAuditListener} is the working reference implementation
 * of the pattern V108 describes.
 *
 * <p>Distinct from {@link ContractTypeAudit}, which covers agreement-level (contract type)
 * mutations and is keyed by {@code contract_type_code}. V394 deliberately created that as a
 * separate table rather than widening this one.
 *
 * <p>Indexes live in the migrations, not in this mapping: {@code idx_audit_contract},
 * {@code idx_audit_timestamp}, {@code idx_audit_user} (V106) and
 * {@code idx_audit_contract_timeline}, {@code idx_audit_user_activity},
 * {@code idx_audit_rule_history}, {@code idx_audit_action_filter} (V107).
 *
 * @see ContractTypeAudit
 */
@Entity
@Table(name = "contract_rule_audit")
@Data
@EqualsAndHashCode(onlyExplicitlyIncluded = true, callSuper = false)
public class ContractRuleAudit extends PanacheEntityBase {

    /**
     * Which class of override rule the entry refers to. Mirrors the
     * {@code ENUM('VALIDATION','RATE_ADJUSTMENT','PRICING')} column.
     */
    public enum RuleType {
        VALIDATION,
        RATE_ADJUSTMENT,
        PRICING
    }

    /**
     * Action performed on the rule. Mirrors the
     * {@code ENUM('CREATE','UPDATE','DELETE','DISABLE','ENABLE')} column — note this is the
     * override lifecycle, not raw SQL verbs: {@code DISABLE}/{@code ENABLE} record an
     * override being deactivated or reactivated without being removed.
     */
    public enum Action {
        CREATE,
        UPDATE,
        DELETE,
        DISABLE,
        ENABLE
    }

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @EqualsAndHashCode.Include
    private Long id;

    /**
     * The contract this audit entry relates to.
     */
    @Column(name = "contract_uuid", nullable = false, length = 36)
    private String contractUuid;

    @Enumerated(EnumType.STRING)
    @Column(name = "rule_type", nullable = false)
    private RuleType ruleType;

    /**
     * Identifier of the specific rule that was modified.
     */
    @Column(name = "rule_id", nullable = false, length = 64)
    private String ruleId;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private Action action;

    /**
     * JSON snapshot of the rule before the change; null for {@link Action#CREATE}.
     */
    @Column(name = "old_value", columnDefinition = "JSON")
    private String oldValue;

    /**
     * JSON snapshot of the rule after the change; null for {@link Action#DELETE}.
     */
    @Column(name = "new_value", columnDefinition = "JSON")
    private String newValue;

    /**
     * UUID of the user who performed the action, per the {@code X-Requested-By} convention.
     */
    @Column(name = "user_id", length = 36)
    private String userId;

    /**
     * When the action occurred.
     *
     * <p>The DB column is literally named {@code timestamp}. The Java field deliberately is
     * not: {@code timestamp} is an HQL keyword, so {@code ORDER BY timestamp DESC} is
     * ambiguous with a timestamp literal. Queries use {@code changedAt}; the column mapping
     * keeps the physical name.
     */
    @Column(name = "timestamp", nullable = false, updatable = false)
    private LocalDateTime changedAt;

    // --- Panache finder methods ---

    /**
     * Find all audit entries for a contract, newest first.
     *
     * @param contractUuid the contract UUID
     * @return newest-first list of audit entries
     */
    public static List<ContractRuleAudit> findByContractOrderByDate(String contractUuid) {
        return find("contractUuid = ?1 ORDER BY changedAt DESC", contractUuid).list();
    }

    /**
     * Find audit entries for a specific contract and rule.
     *
     * @param contractUuid the contract UUID
     * @param ruleId       the rule ID
     * @return newest-first list of audit entries
     */
    public static List<ContractRuleAudit> findByContractAndRule(String contractUuid, String ruleId) {
        return find("contractUuid = ?1 AND ruleId = ?2 ORDER BY changedAt DESC",
            contractUuid, ruleId).list();
    }

    /**
     * Find recent audit entries across all contracts.
     *
     * @param limit maximum number of entries to return
     * @return newest-first list of recent audit entries
     */
    public static List<ContractRuleAudit> findRecentEntries(int limit) {
        return find("ORDER BY changedAt DESC").page(0, limit).list();
    }

    /**
     * Find audit entries by the user who performed the action.
     *
     * @param userId UUID of the acting user
     * @return newest-first list of audit entries
     */
    public static List<ContractRuleAudit> findByUserId(String userId) {
        return find("userId = ?1 ORDER BY changedAt DESC", userId).list();
    }

    /**
     * Find audit entries by action.
     *
     * @param action the action performed
     * @return newest-first list of audit entries
     */
    public static List<ContractRuleAudit> findByAction(Action action) {
        return find("action = ?1 ORDER BY changedAt DESC", action).list();
    }

    /**
     * Find audit entries by rule type.
     *
     * @param ruleType the class of override rule
     * @return newest-first list of audit entries
     */
    public static List<ContractRuleAudit> findByRuleType(RuleType ruleType) {
        return find("ruleType = ?1 ORDER BY changedAt DESC", ruleType).list();
    }

    /**
     * Find audit entries for a contract within a date range.
     *
     * @param contractUuid the contract UUID
     * @param from         start of range (inclusive)
     * @param to           end of range (exclusive)
     * @return newest-first list of audit entries in the range
     */
    public static List<ContractRuleAudit> findByContractAndDateRange(
        String contractUuid, LocalDateTime from, LocalDateTime to) {
        return find("contractUuid = ?1 AND changedAt >= ?2 AND changedAt < ?3 ORDER BY changedAt DESC",
            contractUuid, from, to).list();
    }

    /**
     * Count audit entries for a contract.
     *
     * @param contractUuid the contract UUID
     * @return number of audit entries
     */
    public static long countByContract(String contractUuid) {
        return count("contractUuid = ?1", contractUuid);
    }

    /**
     * Get the most recent audit entry for a contract and rule.
     *
     * @param contractUuid the contract UUID
     * @param ruleId       the rule ID
     * @return the most recent entry, or null if none exists
     */
    public static ContractRuleAudit findLatestByContractAndRule(String contractUuid, String ruleId) {
        return find("contractUuid = ?1 AND ruleId = ?2 ORDER BY changedAt DESC",
            contractUuid, ruleId).firstResult();
    }
}
