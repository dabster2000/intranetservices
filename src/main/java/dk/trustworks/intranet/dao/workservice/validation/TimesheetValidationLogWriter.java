package dk.trustworks.intranet.dao.workservice.validation;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import dk.trustworks.intranet.contracts.config.TimesheetRuleEnforcementConfig.Mode;
import dk.trustworks.intranet.dao.workservice.model.Work;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.persistence.EntityManager;
import jakarta.transaction.Transactional;
import lombok.extern.jbosslog.JBossLog;

import java.util.LinkedHashMap;
import java.util.Map;

/** Writes one durable, comment-free audit row for each timesheet validation failure. */
@ApplicationScoped
@JBossLog
public class TimesheetValidationLogWriter {

    private static final int MAX_ID_LENGTH = 36;
    private static final int MAX_ERROR_LENGTH = 2_000;

    @Inject
    EntityManager em;

    @Inject
    ObjectMapper objectMapper;

    /**
     * REQUIRES_NEW is required even when the caller later throws: enforcement rollback must never
     * erase the evidence used to evaluate the LOG_ONLY rollout and diagnose rejected requests.
     */
    @Transactional(Transactional.TxType.REQUIRES_NEW)
    public void recordViolation(
            Work work,
            String effectiveUserUuid,
            String contractUuid,
            String projectUuid,
            String requestedByUuid,
            Mode mode,
            TimesheetRuleViolation violation) {

        String requestData = serializeRequestMetadata(
                work, effectiveUserUuid, contractUuid, projectUuid, requestedByUuid, mode);

        em.createNativeQuery("""
                        INSERT INTO contract_validation_log
                            (entity_type, entity_uuid, contract_uuid, user_uuid, project_uuid,
                             error_type, error_message, request_data)
                        VALUES
                            ('WORK', :entityUuid, :contractUuid, :userUuid, :projectUuid,
                             :errorType, :errorMessage, :requestData)
                        """)
                .setParameter("entityUuid", bounded(work.getUuid(), MAX_ID_LENGTH))
                .setParameter("contractUuid", bounded(contractUuid, MAX_ID_LENGTH))
                .setParameter("userUuid", bounded(effectiveUserUuid, MAX_ID_LENGTH))
                .setParameter("projectUuid", bounded(projectUuid, MAX_ID_LENGTH))
                .setParameter("errorType", bounded(violation.type(), 100))
                .setParameter("errorMessage", bounded(violation.message(), MAX_ERROR_LENGTH))
                .setParameter("requestData", requestData)
                .executeUpdate();
    }

    private String serializeRequestMetadata(
            Work work,
            String effectiveUserUuid,
            String contractUuid,
            String projectUuid,
            String requestedByUuid,
            Mode mode) {
        Map<String, Object> metadata = new LinkedHashMap<>();
        metadata.put("workUuid", bounded(work.getUuid(), MAX_ID_LENGTH));
        metadata.put("userUuid", bounded(work.getUseruuid(), MAX_ID_LENGTH));
        metadata.put("effectiveUserUuid", bounded(effectiveUserUuid, MAX_ID_LENGTH));
        metadata.put("contractUuid", bounded(contractUuid, MAX_ID_LENGTH));
        metadata.put("projectUuid", bounded(projectUuid, MAX_ID_LENGTH));
        metadata.put("taskUuid", bounded(work.getTaskuuid(), MAX_ID_LENGTH));
        metadata.put("registered", work.getRegistered());
        metadata.put("workDuration", work.getWorkduration());
        metadata.put("commentPresent", work.getComments() != null && !work.getComments().isBlank());
        metadata.put("commentLength", work.getComments() == null ? 0 : work.getComments().length());
        metadata.put("requestedBy", bounded(requestedByUuid, MAX_ID_LENGTH));
        metadata.put("mode", mode.name());

        try {
            return objectMapper.writeValueAsString(metadata);
        } catch (JsonProcessingException exception) {
            // All fields are bounded primitives, but keep audit insertion fail-safe if a custom
            // ObjectMapper module unexpectedly rejects one of them. Never fall back to comment text.
            log.warnf("Could not serialize bounded timesheet validation metadata: %s", exception.getMessage());
            return "{}";
        }
    }

    private static String bounded(String value, int maxLength) {
        if (value == null) {
            return null;
        }
        String normalized = value.trim();
        return normalized.length() <= maxLength ? normalized : normalized.substring(0, maxLength);
    }
}
