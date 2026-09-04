package dk.trustworks.intranet.contracts.services;

import dk.trustworks.intranet.contracts.model.ContractRuleAudit;
import jakarta.enterprise.context.ApplicationScoped;
import lombok.extern.jbosslog.JBossLog;

import java.time.LocalDateTime;
import java.util.List;

/**
 * Service for querying the contract rule override audit trail.
 *
 * <p>Read-only access to {@code contract_rule_audit} (V106). Every query is keyed by
 * {@code contractUuid} and ordered newest-first on {@code changedAt} (the {@code timestamp}
 * column), which matches the V107 index {@code idx_audit_contract_timeline}.
 *
 * <p><b>The table has no writer.</b> V108 chose an application-layer
 * {@code @EntityListeners} design over DB triggers but the listener was never built, so
 * these methods return empty results until one exists. See {@link ContractRuleAudit}.
 *
 * @see ContractRuleAudit
 */
@ApplicationScoped
@JBossLog
public class ContractRuleAuditService {

    /**
     * Get complete audit history for a contract.
     *
     * @param contractUuid The contract UUID
     * @return List of audit entries, newest first
     */
    public List<ContractRuleAudit> getAuditHistory(String contractUuid) {
        log.debugf("Getting audit history for contract %s", contractUuid);

        List<ContractRuleAudit> audits = ContractRuleAudit.find(
            "contractUuid = ?1 ORDER BY changedAt DESC",
            contractUuid
        ).list();

        log.debugf("Found %d audit entries for contract %s", audits.size(), contractUuid);
        return audits;
    }

    /**
     * Get audit history for a specific contract and rule.
     * Useful for tracking changes to a single rule over time.
     *
     * @param contractUuid The contract UUID
     * @param ruleId The rule ID
     * @return List of audit entries for the specified rule, newest first
     */
    public List<ContractRuleAudit> getAuditForRule(String contractUuid, String ruleId) {
        log.debugf("Getting audit history for contract %s, rule %s", contractUuid, ruleId);

        List<ContractRuleAudit> audits = ContractRuleAudit.find(
            "contractUuid = ?1 AND ruleId = ?2 ORDER BY changedAt DESC",
            contractUuid, ruleId
        ).list();

        log.debugf("Found %d audit entries for rule %s", audits.size(), ruleId);
        return audits;
    }

    /**
     * Get audit history filtered by the class of override rule.
     *
     * @param contractUuid The contract UUID
     * @param ruleType VALIDATION, RATE_ADJUSTMENT or PRICING
     * @return List of audit entries for the specified rule type, newest first
     */
    public List<ContractRuleAudit> getAuditByRuleType(String contractUuid,
                                                      ContractRuleAudit.RuleType ruleType) {
        log.debugf("Getting audit history for contract %s, rule type %s", contractUuid, ruleType);

        List<ContractRuleAudit> audits = ContractRuleAudit.find(
            "contractUuid = ?1 AND ruleType = ?2 ORDER BY changedAt DESC",
            contractUuid, ruleType
        ).list();

        log.debugf("Found %d audit entries for rule type %s", audits.size(), ruleType);
        return audits;
    }

    /**
     * Get audit history filtered by action.
     *
     * @param contractUuid The contract UUID
     * @param action CREATE, UPDATE, DELETE, DISABLE or ENABLE
     * @return List of audit entries for the specified action, newest first
     */
    public List<ContractRuleAudit> getAuditByAction(String contractUuid,
                                                    ContractRuleAudit.Action action) {
        log.debugf("Getting audit history for contract %s, action %s", contractUuid, action);

        List<ContractRuleAudit> audits = ContractRuleAudit.find(
            "contractUuid = ?1 AND action = ?2 ORDER BY changedAt DESC",
            contractUuid, action
        ).list();

        log.debugf("Found %d audit entries for action %s", audits.size(), action);
        return audits;
    }

    /**
     * Get audit history filtered by the user who performed the action.
     *
     * @param contractUuid The contract UUID
     * @param userId The acting user's UUID
     * @return List of audit entries for the specified user, newest first
     */
    public List<ContractRuleAudit> getAuditByUser(String contractUuid, String userId) {
        log.debugf("Getting audit history for contract %s, user %s", contractUuid, userId);

        List<ContractRuleAudit> audits = ContractRuleAudit.find(
            "contractUuid = ?1 AND userId = ?2 ORDER BY changedAt DESC",
            contractUuid, userId
        ).list();

        log.debugf("Found %d audit entries for user %s", audits.size(), userId);
        return audits;
    }

    /**
     * Get audit history within a date range.
     *
     * @param contractUuid The contract UUID
     * @param startDate Start of date range (inclusive)
     * @param endDate End of date range (inclusive)
     * @return List of audit entries within the date range, newest first
     */
    public List<ContractRuleAudit> getAuditByDateRange(
        String contractUuid,
        LocalDateTime startDate,
        LocalDateTime endDate
    ) {
        log.debugf("Getting audit history for contract %s between %s and %s",
            contractUuid, startDate, endDate);

        List<ContractRuleAudit> audits = ContractRuleAudit.find(
            "contractUuid = ?1 AND changedAt >= ?2 AND changedAt <= ?3 ORDER BY changedAt DESC",
            contractUuid, startDate, endDate
        ).list();

        log.debugf("Found %d audit entries in date range", audits.size());
        return audits;
    }

    /**
     * Get recent audit history (last N entries).
     *
     * @param contractUuid The contract UUID
     * @param limit Maximum number of entries to return
     * @return List of most recent audit entries, newest first
     */
    public List<ContractRuleAudit> getRecentAudit(String contractUuid, int limit) {
        log.debugf("Getting last %d audit entries for contract %s", limit, contractUuid);

        List<ContractRuleAudit> audits = ContractRuleAudit.find(
            "contractUuid = ?1 ORDER BY changedAt DESC",
            contractUuid
        ).page(0, limit).list();

        log.debugf("Returning %d recent audit entries", audits.size());
        return audits;
    }

    /**
     * Get count of audit entries for a contract.
     * Useful for pagination and UI display.
     *
     * @param contractUuid The contract UUID
     * @return Total number of audit entries
     */
    public long getAuditCount(String contractUuid) {
        long count = ContractRuleAudit.count("contractUuid = ?1", contractUuid);
        log.debugf("Contract %s has %d audit entries", contractUuid, count);
        return count;
    }

    /**
     * Get count of audit entries for a specific rule.
     *
     * @param contractUuid The contract UUID
     * @param ruleId The rule ID
     * @return Number of audit entries for the rule
     */
    public long getAuditCountForRule(String contractUuid, String ruleId) {
        long count = ContractRuleAudit.count(
            "contractUuid = ?1 AND ruleId = ?2",
            contractUuid, ruleId
        );
        log.debugf("Rule %s has %d audit entries", ruleId, count);
        return count;
    }
}
