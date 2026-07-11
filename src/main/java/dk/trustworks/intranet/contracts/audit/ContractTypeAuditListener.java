package dk.trustworks.intranet.contracts.audit;

import dk.trustworks.intranet.contracts.model.ContractTypeAudit;
import dk.trustworks.intranet.contracts.model.ContractTypeDefinition;
import dk.trustworks.intranet.contracts.model.ContractValidationRuleEntity;
import dk.trustworks.intranet.contracts.model.PricingRuleStepEntity;
import dk.trustworks.intranet.security.RequestHeaderHolder;
import io.quarkus.hibernate.orm.panache.Panache;
import jakarta.enterprise.inject.spi.CDI;
import jakarta.persistence.PostPersist;
import jakarta.persistence.PostRemove;
import jakarta.persistence.PreUpdate;
import lombok.extern.jbosslog.JBossLog;
import org.hibernate.Session;

import java.math.BigDecimal;
import java.sql.Connection;
import java.sql.Date;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/**
 * JPA entity listener that writes the framework agreement audit trail
 * ({@code contract_type_audit}, V394) for {@link ContractTypeDefinition},
 * {@link PricingRuleStepEntity} and {@link ContractValidationRuleEntity}.
 *
 * <p>Recorded operations:
 * <ul>
 *   <li>{@code CREATE} — on persist ({@link PostPersist})</li>
 *   <li>{@code UPDATE} — on update with field changes</li>
 *   <li>{@code DELETE} — on soft-disable ({@code active: true -> false}) or hard delete</li>
 *   <li>{@code RESTORE} — on re-activation ({@code active: false -> true})</li>
 * </ul>
 *
 * <p>Old state for the field diff is read with plain JDBC in {@link PreUpdate} — the row
 * still holds the pre-update values because the entity's UPDATE statement has not executed
 * yet. Audit rows are inserted with plain JDBC via {@link Session#doWork} on the session's
 * own connection (persisting entities from inside JPA lifecycle callbacks is unsafe
 * mid-flush), so they commit/roll back atomically with the audited mutation.
 *
 * <p>Attribution comes from {@link RequestHeaderHolder} (X-Requested-By header via
 * {@code HeaderInterceptor}) — never from the JWT. When no user can be resolved (header
 * absent → "anonymous", or no request context at all) {@code changed_by} is NULL.
 *
 * <p>Audit failures are logged but never fail the business transaction (same policy as
 * {@link dk.trustworks.intranet.security.AuditEntityListener}).
 */
@JBossLog
public class ContractTypeAuditListener {

    private static final int SUMMARY_MAX_LENGTH = 1000;

    private static final String INSERT_SQL =
            "INSERT INTO contract_type_audit (contract_type_code, entity_type, rule_id, operation, changed_by, summary) " +
            "VALUES (?, ?, ?, ?, ?, ?)";

    private static final String SELECT_AGREEMENT_OLD_STATE =
            "SELECT name, description, valid_from, valid_until, active FROM contract_type_definitions WHERE id = ?";

    private static final String SELECT_PRICING_RULE_OLD_STATE =
            "SELECT label, rule_step_type, step_base, purpose, percent, amount, param_key, valid_from, valid_to, priority, active " +
            "FROM pricing_rule_steps WHERE id = ?";

    private static final String SELECT_VALIDATION_RULE_OLD_STATE =
            "SELECT label, validation_type, required, threshold_value, config_json, priority, active " +
            "FROM contract_validation_rules WHERE id = ?";

    // --- JPA lifecycle callbacks ---

    @PostPersist
    public void postPersist(Object entity) {
        try {
            if (entity instanceof ContractTypeDefinition agreement) {
                insertAuditRow(agreement.getCode(), ContractTypeAudit.EntityType.AGREEMENT, null,
                        ContractTypeAudit.Operation.CREATE,
                        truncate("created agreement " + quote(agreement.getName())));
            } else if (entity instanceof PricingRuleStepEntity rule) {
                insertAuditRow(rule.getContractTypeCode(), ContractTypeAudit.EntityType.PRICING_RULE, rule.getRuleId(),
                        ContractTypeAudit.Operation.CREATE,
                        truncate("created pricing rule " + quote(rule.getLabel())));
            } else if (entity instanceof ContractValidationRuleEntity rule) {
                insertAuditRow(rule.getContractTypeCode(), ContractTypeAudit.EntityType.VALIDATION_RULE, rule.getRuleId(),
                        ContractTypeAudit.Operation.CREATE,
                        truncate("created validation rule " + quote(rule.getLabel())));
            }
        } catch (Exception e) {
            log.error("Failed to write CREATE audit row for " + entity.getClass().getSimpleName(), e);
        }
    }

    @PreUpdate
    public void preUpdate(Object entity) {
        try {
            if (entity instanceof ContractTypeDefinition agreement) {
                auditAgreementUpdate(agreement);
            } else if (entity instanceof PricingRuleStepEntity rule) {
                auditPricingRuleUpdate(rule);
            } else if (entity instanceof ContractValidationRuleEntity rule) {
                auditValidationRuleUpdate(rule);
            }
        } catch (Exception e) {
            log.error("Failed to write UPDATE audit row for " + entity.getClass().getSimpleName(), e);
        }
    }

    @PostRemove
    public void postRemove(Object entity) {
        try {
            if (entity instanceof ContractTypeDefinition agreement) {
                insertAuditRow(agreement.getCode(), ContractTypeAudit.EntityType.AGREEMENT, null,
                        ContractTypeAudit.Operation.DELETE, "hard-deleted agreement");
            } else if (entity instanceof PricingRuleStepEntity rule) {
                insertAuditRow(rule.getContractTypeCode(), ContractTypeAudit.EntityType.PRICING_RULE, rule.getRuleId(),
                        ContractTypeAudit.Operation.DELETE, "hard-deleted pricing rule");
            } else if (entity instanceof ContractValidationRuleEntity rule) {
                insertAuditRow(rule.getContractTypeCode(), ContractTypeAudit.EntityType.VALIDATION_RULE, rule.getRuleId(),
                        ContractTypeAudit.Operation.DELETE, "hard-deleted validation rule");
            }
        } catch (Exception e) {
            log.error("Failed to write DELETE audit row for " + entity.getClass().getSimpleName(), e);
        }
    }

    // --- Per-entity update auditing (old state via JDBC, diff, classify, insert) ---

    private void auditAgreementUpdate(ContractTypeDefinition entity) {
        onSessionConnection(connection -> {
            try (PreparedStatement select = connection.prepareStatement(SELECT_AGREEMENT_OLD_STATE)) {
                select.setInt(1, entity.getId());
                try (ResultSet rs = select.executeQuery()) {
                    if (!rs.next()) {
                        return; // row vanished — nothing trustworthy to diff against
                    }
                    boolean oldActive = rs.getBoolean("active");
                    List<String> changes = new ArrayList<>();
                    addChange(changes, "name", rs.getString("name"), entity.getName());
                    addChange(changes, "description", rs.getString("description"), entity.getDescription());
                    addChange(changes, "validFrom", toLocalDate(rs.getDate("valid_from")), entity.getValidFrom());
                    addChange(changes, "validUntil", toLocalDate(rs.getDate("valid_until")), entity.getValidUntil());
                    addChange(changes, "active", oldActive, entity.isActive());
                    if (changes.isEmpty()) {
                        return; // no tracked field changed — skip noise
                    }
                    insertAuditRow(connection, entity.getCode(), ContractTypeAudit.EntityType.AGREEMENT, null,
                            classifyOperation(oldActive, entity.isActive()), truncate(String.join("; ", changes)));
                }
            }
        });
    }

    private void auditPricingRuleUpdate(PricingRuleStepEntity entity) {
        onSessionConnection(connection -> {
            try (PreparedStatement select = connection.prepareStatement(SELECT_PRICING_RULE_OLD_STATE)) {
                select.setInt(1, entity.getId());
                try (ResultSet rs = select.executeQuery()) {
                    if (!rs.next()) {
                        return;
                    }
                    boolean oldActive = rs.getBoolean("active");
                    List<String> changes = new ArrayList<>();
                    addChange(changes, "label", rs.getString("label"), entity.getLabel());
                    addChange(changes, "ruleStepType", rs.getString("rule_step_type"), enumName(entity.getRuleStepType()));
                    addChange(changes, "stepBase", rs.getString("step_base"), enumName(entity.getStepBase()));
                    addChange(changes, "purpose", rs.getString("purpose"), enumName(entity.getPurpose()));
                    addChange(changes, "percent", rs.getBigDecimal("percent"), entity.getPercent());
                    addChange(changes, "amount", rs.getBigDecimal("amount"), entity.getAmount());
                    addChange(changes, "paramKey", rs.getString("param_key"), entity.getParamKey());
                    addChange(changes, "validFrom", toLocalDate(rs.getDate("valid_from")), entity.getValidFrom());
                    addChange(changes, "validTo", toLocalDate(rs.getDate("valid_to")), entity.getValidTo());
                    addChange(changes, "priority", toInteger(rs, "priority"), entity.getPriority());
                    addChange(changes, "active", oldActive, entity.isActive());
                    if (changes.isEmpty()) {
                        return;
                    }
                    insertAuditRow(connection, entity.getContractTypeCode(), ContractTypeAudit.EntityType.PRICING_RULE,
                            entity.getRuleId(), classifyOperation(oldActive, entity.isActive()),
                            truncate(String.join("; ", changes)));
                }
            }
        });
    }

    private void auditValidationRuleUpdate(ContractValidationRuleEntity entity) {
        onSessionConnection(connection -> {
            try (PreparedStatement select = connection.prepareStatement(SELECT_VALIDATION_RULE_OLD_STATE)) {
                select.setInt(1, entity.getId());
                try (ResultSet rs = select.executeQuery()) {
                    if (!rs.next()) {
                        return;
                    }
                    boolean oldActive = rs.getBoolean("active");
                    List<String> changes = new ArrayList<>();
                    addChange(changes, "label", rs.getString("label"), entity.getLabel());
                    addChange(changes, "validationType", rs.getString("validation_type"), enumName(entity.getValidationType()));
                    addChange(changes, "required", rs.getBoolean("required"), entity.isRequired());
                    addChange(changes, "thresholdValue", rs.getBigDecimal("threshold_value"), entity.getThresholdValue());
                    addChange(changes, "configJson", rs.getString("config_json"), entity.getConfigJson());
                    addChange(changes, "priority", toInteger(rs, "priority"), entity.getPriority());
                    addChange(changes, "active", oldActive, entity.isActive());
                    if (changes.isEmpty()) {
                        return;
                    }
                    insertAuditRow(connection, entity.getContractTypeCode(), ContractTypeAudit.EntityType.VALIDATION_RULE,
                            entity.getRuleId(), classifyOperation(oldActive, entity.isActive()),
                            truncate(String.join("; ", changes)));
                }
            }
        });
    }

    // --- Insert ---

    private void insertAuditRow(String contractTypeCode, ContractTypeAudit.EntityType entityType, String ruleId,
                                ContractTypeAudit.Operation operation, String summary) {
        onSessionConnection(connection ->
                insertAuditRow(connection, contractTypeCode, entityType, ruleId, operation, summary));
    }

    private void insertAuditRow(Connection connection, String contractTypeCode, ContractTypeAudit.EntityType entityType,
                                String ruleId, ContractTypeAudit.Operation operation, String summary) throws SQLException {
        String changedBy = currentUser();
        try (PreparedStatement insert = connection.prepareStatement(INSERT_SQL)) {
            insert.setString(1, contractTypeCode);
            insert.setString(2, entityType.name());
            insert.setString(3, ruleId);
            insert.setString(4, operation.name());
            insert.setString(5, changedBy);
            insert.setString(6, summary);
            insert.executeUpdate();
        }
        log.debugf("Audit: %s %s %s/%s by %s — %s", operation, entityType, contractTypeCode, ruleId, changedBy, summary);
    }

    /**
     * Run JDBC work on the current Hibernate session's own connection ({@link Session#doWork}),
     * so audit reads/writes share the exact connection and transaction of the audited mutation.
     */
    private void onSessionConnection(org.hibernate.jdbc.Work work) {
        Panache.getEntityManager().unwrap(Session.class).doWork(work);
    }

    /**
     * Resolve the acting user from the request context. Null-safe: returns null when no
     * request context is active (batch/startup) or when the request was unattributed
     * (HeaderInterceptor defaults to "anonymous" when X-Requested-By is absent).
     */
    private String currentUser() {
        try {
            RequestHeaderHolder holder = CDI.current().select(RequestHeaderHolder.class).get();
            return normalizeChangedBy(holder.getUserUuid());
        } catch (Exception e) {
            log.debug("No request context available for audit attribution; changed_by will be null");
            return null;
        }
    }

    // --- Pure helpers (package-private for unit tests) ---

    /**
     * Classify an update: an active-flag flip dominates ({@code true -> false} = DELETE
     * soft-disable, {@code false -> true} = RESTORE), anything else is a plain UPDATE.
     */
    static ContractTypeAudit.Operation classifyOperation(boolean oldActive, boolean newActive) {
        if (oldActive && !newActive) {
            return ContractTypeAudit.Operation.DELETE;
        }
        if (!oldActive && newActive) {
            return ContractTypeAudit.Operation.RESTORE;
        }
        return ContractTypeAudit.Operation.UPDATE;
    }

    /**
     * Map "no attribution" markers to null: blank values and HeaderInterceptor's
     * "anonymous" default (header absent) are stored as NULL changed_by.
     */
    static String normalizeChangedBy(String raw) {
        if (raw == null || raw.isBlank() || "anonymous".equals(raw)) {
            return null;
        }
        return raw;
    }

    /**
     * Append {@code "field: old -> new"} to {@code changes} when the value differs.
     * BigDecimals compare by numeric value (DB scale padding is not a change).
     */
    static void addChange(List<String> changes, String field, Object oldValue, Object newValue) {
        if (valueEquals(oldValue, newValue)) {
            return;
        }
        changes.add(field + ": " + formatValue(oldValue) + " -> " + formatValue(newValue));
    }

    static boolean valueEquals(Object a, Object b) {
        if (a instanceof BigDecimal x && b instanceof BigDecimal y) {
            return x.compareTo(y) == 0;
        }
        return Objects.equals(a, b);
    }

    static String formatValue(Object value) {
        if (value == null) {
            return "null";
        }
        if (value instanceof String s) {
            return quote(s);
        }
        if (value instanceof BigDecimal bd) {
            return bd.stripTrailingZeros().toPlainString();
        }
        return String.valueOf(value);
    }

    static String truncate(String summary) {
        if (summary == null || summary.length() <= SUMMARY_MAX_LENGTH) {
            return summary;
        }
        return summary.substring(0, SUMMARY_MAX_LENGTH - 1) + "…";
    }

    private static String quote(String s) {
        return s == null ? "null" : "'" + s + "'";
    }

    private static String enumName(Enum<?> value) {
        return value == null ? null : value.name();
    }

    private static LocalDate toLocalDate(Date date) {
        return date == null ? null : date.toLocalDate();
    }

    private static Integer toInteger(ResultSet rs, String column) throws SQLException {
        int value = rs.getInt(column);
        return rs.wasNull() ? null : value;
    }
}
