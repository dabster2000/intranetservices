package dk.trustworks.intranet.userservice.services;

import dk.trustworks.intranet.batch.monitoring.BatchExceptionTracking;
import jakarta.batch.api.AbstractBatchlet;
import jakarta.enterprise.context.Dependent;
import jakarta.enterprise.context.control.ActivateRequestContext;
import jakarta.inject.Inject;
import jakarta.inject.Named;
import lombok.extern.jbosslog.JBossLog;

@JBossLog
@Named("teamDescriptionBatchlet")
@Dependent
@BatchExceptionTracking
public class TeamDescriptionBatchlet extends AbstractBatchlet {

    @Inject
    TeamService teamService;

    @Override
    @ActivateRequestContext
    public String process() throws Exception {
        try {
            teamService.updateTeamDescription();
            return "COMPLETED";
        } catch (Exception e) {
            log.error("TeamDescriptionBatchlet failed", e);
            throw e;
        }
    }
}
