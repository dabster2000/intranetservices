package dk.trustworks.intranet.recruitmentservice.jobs;

import dk.trustworks.intranet.batch.monitoring.MonitoredBatchlet;
import dk.trustworks.intranet.recruitmentservice.services.RecruitmentMorningBriefService;
import jakarta.enterprise.context.Dependent;
import jakarta.inject.Inject;
import jakarta.inject.Named;
import lombok.extern.jbosslog.JBossLog;

/**
 * The eve interviewer brief (V531): one DM per interviewer the working day
 * before their interviews — the preparation pack, sent while there is still
 * a working day left to read the CV, skim the answers and move something.
 * See {@link RecruitmentMorningBriefService} for content, gating, the
 * working-day-before window and the event-derived
 * per-(interviewer, interview, date) idempotency that makes re-runs and
 * concurrent instances harmless. A no-op while
 * {@code recruitment.pipeline.enabled} or
 * {@code recruitment.slack.eve-brief.enabled} is off.
 * <p>
 * Scheduled once per day at 13:00 UTC by {@code BatchScheduler} (15:00 in
 * Copenhagen in summer, 14:00 in winter — mid-afternoon, late enough that
 * the day's own interviews are done and early enough to still act on what
 * it says), gated by {@code dk.trustworks.recruitment.eve-brief.enabled}.
 * A run on a Saturday or Sunday sends nothing: no interview date has a
 * weekend day as its working-day-before.
 */
@JBossLog
@Dependent
@Named("recruitmentEveBriefBatchlet")
public class RecruitmentEveBriefBatchlet extends MonitoredBatchlet {

    @Inject
    RecruitmentMorningBriefService briefService;

    @Override
    protected String doProcess() {
        RecruitmentMorningBriefService.BriefSummary summary = briefService.runEve();
        if (summary.briefsSent() > 0 || summary.failures() > 0) {
            log.infof("recruitment-eve-brief: %s", summary);
        }
        return "COMPLETED " + summary;
    }
}
