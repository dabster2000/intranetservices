package dk.trustworks.intranet.recruitmentservice.jobs;

import dk.trustworks.intranet.batch.monitoring.MonitoredBatchlet;
import dk.trustworks.intranet.recruitmentservice.services.RecruitmentSlaService;
import jakarta.enterprise.context.Dependent;
import jakarta.inject.Inject;
import jakarta.inject.Named;
import lombok.extern.jbosslog.JBossLog;

/**
 * Daily SLA sweep (ATS expansion P17): chases overdue scorecards, stalled
 * debriefs and idle candidates with Slack DMs — see
 * {@link RecruitmentSlaService} for triggers, thresholds and the
 * event-derived idempotency that makes re-runs and concurrent instances
 * harmless. A no-op while {@code recruitment.interviews.enabled} is off.
 * <p>
 * Scheduled once per day at 07:00 UTC by {@code BatchScheduler} (a morning
 * nudge lands at the start of the workday — a literal middle-of-the-night
 * run would ping phones at 03:00), gated by
 * {@code dk.trustworks.recruitment.sla-sweep.enabled}.
 */
@JBossLog
@Dependent
@Named("recruitmentSlaSweepBatchlet")
public class RecruitmentSlaSweepBatchlet extends MonitoredBatchlet {

    @Inject
    RecruitmentSlaService slaService;

    @Override
    protected String doProcess() {
        RecruitmentSlaService.SweepSummary summary = slaService.sweep();
        if (summary.scorecardNudges() > 0 || summary.debriefNudges() > 0
                || summary.idleNudges() > 0 || summary.failures() > 0) {
            log.infof("recruitment-sla-sweep: %s", summary);
        }
        return "COMPLETED " + summary;
    }
}
