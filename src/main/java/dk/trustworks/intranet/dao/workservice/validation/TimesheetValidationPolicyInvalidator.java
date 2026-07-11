package dk.trustworks.intranet.dao.workservice.validation;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.event.Event;
import jakarta.enterprise.event.Observes;
import jakarta.enterprise.event.TransactionPhase;
import jakarta.inject.Inject;

/** Defers policy-cache invalidation until the surrounding rule mutation commits successfully. */
@ApplicationScoped
public class TimesheetValidationPolicyInvalidator {

    @Inject
    Event<TimesheetValidationPolicyChanged> changes;

    @Inject
    TimesheetValidationPolicyCache policyCache;

    public void scheduleAfterCommit() {
        changes.fire(new TimesheetValidationPolicyChanged());
    }

    void afterSuccessfulCommit(
            @Observes(during = TransactionPhase.AFTER_SUCCESS) TimesheetValidationPolicyChanged ignored) {
        // Cross-bean call is intentional so @CacheInvalidateAll is intercepted.
        policyCache.invalidateAll();
    }
}
