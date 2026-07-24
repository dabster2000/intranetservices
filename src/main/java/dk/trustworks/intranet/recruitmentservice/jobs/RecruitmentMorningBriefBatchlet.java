package dk.trustworks.intranet.recruitmentservice.jobs;

import dk.trustworks.intranet.batch.monitoring.MonitoredBatchlet;
import dk.trustworks.intranet.recruitmentservice.services.RecruitmentMorningBriefService;
import jakarta.enterprise.context.Dependent;
import jakarta.inject.Inject;
import jakarta.inject.Named;
import lombok.extern.jbosslog.JBossLog;

/**
 * Morning interviewer briefs (ATS expansion P23, Slack spec §5.8): one DM
 * per interviewer on days they have scheduled interviews — see
 * {@link RecruitmentMorningBriefService} for content, gating and the
 * event-derived per-(interviewer, interview, date) idempotency that makes
 * re-runs and concurrent instances harmless. A no-op while
 * {@code recruitment.pipeline.enabled} or
 * {@code recruitment.slack.morning-brief.enabled} is off.
 * <p>
 * Scheduled once per day at 06:00 UTC by {@code BatchScheduler} (07:00 or
 * 08:00 in Copenhagen — before the workday and before the 07:00 UTC SLA
 * sweep), gated by {@code dk.trustworks.recruitment.morning-brief.enabled}.
 */
@JBossLog
@Dependent
@Named("recruitmentMorningBriefBatchlet")
public class RecruitmentMorningBriefBatchlet extends MonitoredBatchlet {

    @Inject
    RecruitmentMorningBriefService briefService;

    @Override
    protected String doProcess() {
        RecruitmentMorningBriefService.BriefSummary summary = briefService.run();
        if (summary.briefsSent() > 0 || summary.failures() > 0) {
            log.infof("recruitment-morning-brief: %s", summary);
        }
        return "COMPLETED " + summary;
    }
}
