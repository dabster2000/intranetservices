package dk.trustworks.intranet.recruitmentservice.jobs;

import dk.trustworks.intranet.batch.monitoring.MonitoredBatchlet;
import dk.trustworks.intranet.recruitmentservice.services.RecruitmentSlaService;
import jakarta.enterprise.context.Dependent;
import jakarta.inject.Inject;
import jakarta.inject.Named;
import lombok.extern.jbosslog.JBossLog;

/**
 * The end-of-meeting scorecard prompt (V531): asks each assigned interviewer
 * for their scorecard shortly after the round actually ended, while the
 * impression is still intact. See
 * {@link RecruitmentSlaService#sweepScorecardPrompts()} for the window, the
 * quiet hours and the event-derived idempotency that makes re-runs and
 * concurrent instances harmless. A no-op while
 * {@code recruitment.interviews.enabled} is off.
 * <p>
 * This is the FIRST ask, not a chase. It shares the daily sweep's
 * {@code recruitment.sla.max-scorecard-nudges} budget, so it moves the first
 * ask earlier rather than adding another one: measured on production
 * 2026-08-24, the mean scorecard arrived 24.9 hours after the meeting ended
 * because the only ask fired 24 hours after it STARTED, on a once-daily job.
 * <p>
 * Scheduled every 15 minutes by {@code BatchScheduler} — a prompt that means
 * "you just finished" cannot ride a daily job — gated by
 * {@code dk.trustworks.recruitment.scorecard-prompt.enabled}. Most runs do
 * nothing, which is why it logs only when it sent something.
 */
@JBossLog
@Dependent
@Named("recruitmentScorecardPromptBatchlet")
public class RecruitmentScorecardPromptBatchlet extends MonitoredBatchlet {

    @Inject
    RecruitmentSlaService slaService;

    @Override
    protected String doProcess() {
        RecruitmentSlaService.PromptSummary summary = slaService.sweepScorecardPrompts();
        if (summary.prompts() > 0 || summary.failures() > 0) {
            log.infof("recruitment-scorecard-prompt: %s", summary);
        }
        return "COMPLETED " + summary;
    }
}
