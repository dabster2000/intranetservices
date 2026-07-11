package dk.trustworks.intranet.dao.workservice.services;

import dk.trustworks.intranet.dao.workservice.model.Work;
import dk.trustworks.intranet.dao.workservice.validation.TimesheetRuleValidationException;
import dk.trustworks.intranet.dao.workservice.validation.TimesheetRuleViolation;
import dk.trustworks.intranet.dao.workservice.validation.TimesheetWorkValidationService;
import jakarta.persistence.PersistenceException;
import org.junit.jupiter.api.Test;

import java.time.LocalDate;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.doNothing;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

class WorkServiceTimesheetValidationTest {

    @Test
    void validatesOnceBeforeDuplicateInsertRetry() {
        Work work = work();
        TimesheetWorkValidationService validator = mock(TimesheetWorkValidationService.class);
        WorkService transactionalProxy = mock(WorkService.class);
        when(validator.validate(work)).thenReturn(false);
        doThrow(new PersistenceException("uq_work_user_date_task"))
                .doNothing()
                .when(transactionalProxy).persistOrUpdateInTx(work, false);
        WorkService service = new WorkService();
        service.timesheetWorkValidationService = validator;
        service.self = transactionalProxy;

        service.persistOrUpdate(work);

        verify(validator, times(1)).validate(work);
        verify(transactionalProxy, times(2)).persistOrUpdateInTx(work, false);
    }

    @Test
    void rejectionStopsBeforePersistenceSeamSoBothResourceCallersCannotEmitPostSaveEvents() {
        Work work = work();
        TimesheetWorkValidationService validator = mock(TimesheetWorkValidationService.class);
        WorkService transactionalProxy = mock(WorkService.class);
        TimesheetRuleValidationException rejection = new TimesheetRuleValidationException(
                "TIMESHEET_RULE_VIOLATION", "contract", "TYPE", "Agreement",
                List.of(new TimesheetRuleViolation("notes", "NOTES_REQUIRED", "required", true, false)));
        doThrow(rejection).when(validator).validate(work);
        WorkService service = new WorkService();
        service.timesheetWorkValidationService = validator;
        service.self = transactionalProxy;

        assertThrows(TimesheetRuleValidationException.class, () -> service.persistOrUpdate(work));

        verifyNoInteractions(transactionalProxy);
    }

    private static Work work() {
        Work work = new Work();
        work.setUseruuid("user");
        work.setTaskuuid("task");
        work.setRegistered(LocalDate.of(2026, 7, 10));
        work.setWorkduration(1.0);
        return work;
    }
}
