package dk.trustworks.intranet.dao.workservice.validation;

import jakarta.enterprise.event.Event;
import jakarta.enterprise.event.Observes;
import jakarta.enterprise.event.TransactionPhase;
import org.junit.jupiter.api.Test;

import java.lang.annotation.Annotation;
import java.lang.reflect.Method;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

class TimesheetValidationPolicyInvalidatorTest {

    @Test
    void schedulesEventAndInvalidatesOnlyFromAfterSuccessObserver() throws Exception {
        @SuppressWarnings("unchecked")
        Event<TimesheetValidationPolicyChanged> events = mock(Event.class);
        TimesheetValidationPolicyCache cache = mock(TimesheetValidationPolicyCache.class);
        TimesheetValidationPolicyInvalidator invalidator = new TimesheetValidationPolicyInvalidator();
        invalidator.changes = events;
        invalidator.policyCache = cache;

        invalidator.scheduleAfterCommit();
        verify(events).fire(any(TimesheetValidationPolicyChanged.class));

        invalidator.afterSuccessfulCommit(new TimesheetValidationPolicyChanged());
        verify(cache).invalidateAll();

        Method observer = TimesheetValidationPolicyInvalidator.class.getDeclaredMethod(
                "afterSuccessfulCommit", TimesheetValidationPolicyChanged.class);
        Annotation[] annotations = observer.getParameterAnnotations()[0];
        Observes observes = (Observes) annotations[0];
        assertEquals(TransactionPhase.AFTER_SUCCESS, observes.during());
    }
}
