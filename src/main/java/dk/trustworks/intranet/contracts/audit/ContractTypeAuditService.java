package dk.trustworks.intranet.contracts.audit;

import dk.trustworks.intranet.contracts.dto.ContractTypeAuditEntryDTO;
import dk.trustworks.intranet.contracts.model.ContractTypeAudit;
import dk.trustworks.intranet.domain.user.entity.User;
import jakarta.enterprise.context.ApplicationScoped;
import lombok.extern.jbosslog.JBossLog;

import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

/**
 * Read-side service for the framework agreement audit trail ({@code contract_type_audit}).
 *
 * <p>Returns entries newest-first and resolves {@code changedByName} from the user table
 * with a single batched IN query over the distinct uuid-shaped {@code changed_by} values
 * (non-uuid principals such as {@code system:<client>} keep a null name).
 *
 * @see ContractTypeAuditListener write side
 */
@JBossLog
@ApplicationScoped
public class ContractTypeAuditService {

    static final int DEFAULT_LIMIT = 100;
    static final int MAX_LIMIT = 500;

    private static final Pattern UUID_PATTERN = Pattern.compile(
            "[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}");

    /**
     * Get the audit trail for an agreement, newest first.
     *
     * @param contractTypeCode the agreement code
     * @param limit            requested max entries; clamped to [1, {@value MAX_LIMIT}]
     * @return newest-first audit entries (empty list for unknown codes — no 404, an
     *         archived agreement's history must stay readable)
     */
    public List<ContractTypeAuditEntryDTO> getAuditForContractType(String contractTypeCode, int limit) {
        int effectiveLimit = clampLimit(limit);
        log.debugf("Getting audit trail for contract type %s, limit=%d", contractTypeCode, effectiveLimit);

        List<ContractTypeAudit> entries = ContractTypeAudit.findByContractTypeCode(contractTypeCode, effectiveLimit);
        Map<String, String> namesByUuid = resolveUserNames(entries);

        return entries.stream()
                .map(entry -> new ContractTypeAuditEntryDTO(
                        entry.getId(),
                        entry.getEntityType().name(),
                        entry.getRuleId(),
                        entry.getOperation().name(),
                        entry.getChangedBy(),
                        entry.getChangedBy() == null ? null : namesByUuid.get(entry.getChangedBy()),
                        entry.getChangedAt(),
                        entry.getSummary()))
                .collect(Collectors.toList());
    }

    private Map<String, String> resolveUserNames(List<ContractTypeAudit> entries) {
        Set<String> uuids = entries.stream()
                .map(ContractTypeAudit::getChangedBy)
                .filter(changedBy -> changedBy != null && UUID_PATTERN.matcher(changedBy).matches())
                .collect(Collectors.toSet());
        if (uuids.isEmpty()) {
            return Map.of();
        }
        return User.<User>list("uuid in ?1", List.copyOf(uuids)).stream()
                .collect(Collectors.toMap(User::getUuid, User::getFullname, (first, second) -> first));
    }

    /**
     * Clamp a requested limit to [1, {@value MAX_LIMIT}].
     */
    static int clampLimit(int requested) {
        if (requested < 1) {
            return 1;
        }
        return Math.min(requested, MAX_LIMIT);
    }
}
