package dk.trustworks.intranet.aggregates.users.services;

import dk.trustworks.intranet.batch.monitoring.BatchExceptionTracking;
import jakarta.batch.api.AbstractBatchlet;
import jakarta.enterprise.context.Dependent;
import jakarta.enterprise.context.control.ActivateRequestContext;
import jakarta.inject.Inject;
import jakarta.inject.Named;
import lombok.extern.jbosslog.JBossLog;

@JBossLog
@Named("userResumeUpdateBatchlet")
@Dependent
@BatchExceptionTracking
public class UserResumeUpdateBatchlet extends AbstractBatchlet {

    @Inject
    UserService userService;

    @Override
    @ActivateRequestContext
    public String process() throws Exception {
        try {
            userService.updateResumes();
            return "COMPLETED";
        } catch (Exception e) {
            log.error("UserResumeUpdateBatchlet failed", e);
            throw e;
        }
    }
}
