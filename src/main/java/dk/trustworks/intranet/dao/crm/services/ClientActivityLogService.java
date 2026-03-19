package dk.trustworks.intranet.dao.crm.services;

import dk.trustworks.intranet.dao.crm.model.ClientActivityLog;
import dk.trustworks.intranet.dto.ClientActivityLogDTO;
import dk.trustworks.intranet.domain.user.entity.User;
import dk.trustworks.intranet.security.RequestHeaderHolder;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.transaction.Transactional;
import lombok.extern.jbosslog.JBossLog;

import java.time.LocalDateTime;
import java.util.*;
import java.util.stream.Collectors;

/**
 * Service for logging and querying client activity log entries.
 *
 * <p>All log methods participate in the caller's transaction (REQUIRED by default)
 * so that activity entries are committed atomically with the data change.
 */
@JBossLog
@ApplicationScoped
public class ClientActivityLogService {

    @Inject
    RequestHeaderHolder requestHeaderHolder;

    // --- Logging methods ---

    @Transactional(Transactional.TxType.REQUIRED)
    public void logCreated(String clientUuid, String entityType, String entityUuid, String entityName) {
        logChange(clientUuid, entityType, entityUuid, entityName,
                ClientActivityLog.ACTION_CREATED, null, null, null);
    }

    @Transactional(Transactional.TxType.REQUIRED)
    public void logDeleted(String clientUuid, String entityType, String entityUuid, String entityName) {
        logChange(clientUuid, entityType, entityUuid, entityName,
                ClientActivityLog.ACTION_DELETED, null, null, null);
    }

    @Transactional(Transactional.TxType.REQUIRED)
    public void logFieldChange(String clientUuid, String entityType, String entityUuid, String entityName,
                               String fieldName, String oldValue, String newValue) {
        if (Objects.equals(oldValue, newValue)) return;
        logChange(clientUuid, entityType, entityUuid, entityName,
                ClientActivityLog.ACTION_MODIFIED, fieldName, oldValue, newValue);
    }

    @Transactional(Transactional.TxType.REQUIRED)
    public void logChange(String clientUuid, String entityType, String entityUuid, String entityName,
                          String action, String fieldName, String oldValue, String newValue) {
        ClientActivityLog entry = new ClientActivityLog();
        entry.setClientUuid(clientUuid);
        entry.setEntityType(entityType);
        entry.setEntityUuid(entityUuid);
        entry.setEntityName(entityName);
        entry.setAction(action);
        entry.setFieldName(fieldName);
        entry.setOldValue(oldValue);
        entry.setNewValue(newValue);
        entry.setModifiedBy(resolveCurrentUser());
        entry.setModifiedAt(LocalDateTime.now());
        entry.persist();
    }

    // --- Query methods ---

    public List<ClientActivityLogDTO> getActivityForClient(String clientUuid, int limit) {
        List<ClientActivityLog> entries = ClientActivityLog.findByClientUuid(clientUuid, limit);
        return enrichWithUserNames(entries);
    }

    public List<ClientActivityLogDTO> getActivityForContract(String contractUuid, int limit) {
        List<ClientActivityLog> entries = ClientActivityLog.findByEntityTypeAndUuid(
                ClientActivityLog.TYPE_CONTRACT, contractUuid, limit);
        return enrichWithUserNames(entries);
    }

    // --- Private helpers ---

    private String resolveCurrentUser() {
        String username = requestHeaderHolder.getUserUuid();
        if (username != null && !username.isBlank()) {
            return username;
        }
        return "system";
    }

    private List<ClientActivityLogDTO> enrichWithUserNames(List<ClientActivityLog> entries) {
        // Collect unique user UUIDs
        Set<String> userUuids = entries.stream()
                .map(ClientActivityLog::getModifiedBy)
                .filter(uuid -> uuid != null && !"system".equals(uuid))
                .collect(Collectors.toSet());

        // Batch-resolve user names
        Map<String, User> userMap = new HashMap<>();
        for (String uuid : userUuids) {
            User user = User.findById(uuid);
            if (user != null) {
                userMap.put(uuid, user);
            }
        }

        // Map to DTOs
        return entries.stream()
                .map(entry -> {
                    ClientActivityLogDTO dto = new ClientActivityLogDTO();
                    dto.setId(entry.getId());
                    dto.setClientUuid(entry.getClientUuid());
                    dto.setEntityType(entry.getEntityType());
                    dto.setEntityUuid(entry.getEntityUuid());
                    dto.setEntityName(entry.getEntityName());
                    dto.setAction(entry.getAction());
                    dto.setFieldName(entry.getFieldName());
                    dto.setOldValue(entry.getOldValue());
                    dto.setNewValue(entry.getNewValue());
                    dto.setModifiedBy(entry.getModifiedBy());
                    dto.setModifiedAt(entry.getModifiedAt());

                    User user = userMap.get(entry.getModifiedBy());
                    if (user != null) {
                        String fullName = (user.getFirstname() + " " + user.getLastname()).trim();
                        dto.setModifiedByName(fullName.isEmpty() ? "Unknown" : fullName);
                        dto.setModifiedByInitials(buildInitials(user.getFirstname(), user.getLastname()));
                    } else {
                        dto.setModifiedByName("System");
                        dto.setModifiedByInitials("SY");
                    }

                    return dto;
                })
                .collect(Collectors.toList());
    }

    private String buildInitials(String firstname, String lastname) {
        StringBuilder sb = new StringBuilder();
        if (firstname != null && !firstname.isBlank()) {
            sb.append(Character.toUpperCase(firstname.charAt(0)));
        }
        if (lastname != null && !lastname.isBlank()) {
            sb.append(Character.toUpperCase(lastname.charAt(0)));
        }
        return sb.length() > 0 ? sb.toString() : "??";
    }
}
