package dk.trustworks.intranet.dao.workservice.validation;

import com.fasterxml.jackson.databind.ObjectMapper;
import dk.trustworks.intranet.contracts.config.TimesheetRuleEnforcementConfig.Mode;
import dk.trustworks.intranet.dao.workservice.model.Work;
import jakarta.persistence.EntityManager;
import jakarta.persistence.Query;
import jakarta.transaction.Transactional;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.time.LocalDate;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class TimesheetValidationLogWriterTest {

    @Test
    @SuppressWarnings("unchecked")
    void writesOneRowWithoutPersistingCommentText() throws Exception {
        EntityManager em = mock(EntityManager.class);
        Query query = mock(Query.class);
        ObjectMapper objectMapper = mock(ObjectMapper.class);
        when(em.createNativeQuery(anyString())).thenReturn(query);
        when(query.setParameter(anyString(), any())).thenReturn(query);
        when(query.executeUpdate()).thenReturn(1);
        when(objectMapper.writeValueAsString(any())).thenReturn("{\"bounded\":true}");

        TimesheetValidationLogWriter writer = new TimesheetValidationLogWriter();
        writer.em = em;
        writer.objectMapper = objectMapper;
        Work work = work();
        TimesheetRuleViolation violation = new TimesheetRuleViolation(
                "notes", "NOTES_REQUIRED", "Notes required", true, false);

        writer.recordViolation(
                work, "effective-user", "contract", "project", "requested-by", Mode.ENFORCE, violation);

        ArgumentCaptor<Object> metadataCaptor = ArgumentCaptor.forClass(Object.class);
        verify(objectMapper).writeValueAsString(metadataCaptor.capture());
        Map<String, Object> metadata = (Map<String, Object>) metadataCaptor.getValue();
        assertFalse(metadata.containsKey("comments"));
        assertFalse(metadata.values().contains("secret customer note"));
        assertEquals(true, metadata.get("commentPresent"));
        assertEquals("requested-by", metadata.get("requestedBy"));
        verify(query).executeUpdate();
    }

    @Test
    void usesRequiresNewSoEnforcementRollbackCannotEraseAudit() throws NoSuchMethodException {
        Transactional annotation = TimesheetValidationLogWriter.class
                .getMethod(
                        "recordViolation",
                        Work.class,
                        String.class,
                        String.class,
                        String.class,
                        String.class,
                        Mode.class,
                        TimesheetRuleViolation.class)
                .getAnnotation(Transactional.class);

        assertNotNull(annotation);
        assertEquals(Transactional.TxType.REQUIRES_NEW, annotation.value());
    }

    private static Work work() {
        Work work = new Work();
        work.setUuid("work");
        work.setUseruuid("user");
        work.setTaskuuid("task");
        work.setRegistered(LocalDate.of(2026, 7, 10));
        work.setWorkduration(1.0);
        work.setComments("secret customer note");
        return work;
    }
}
