package dk.trustworks.intranet.recruitmentservice.jobs;

import dk.trustworks.intranet.batch.monitoring.MonitoredBatchlet;
import dk.trustworks.intranet.recruitmentservice.ai.AiDigestService;
import dk.trustworks.intranet.recruitmentservice.services.RecruitmentDpoDigestService;
import jakarta.enterprise.context.Dependent;
import jakarta.inject.Inject;
import jakarta.inject.Named;
import lombok.extern.jbosslog.JBossLog;

/**
 * The P24 digests (AI spec §5.5, Slack spec §5.9–5.10): the weekly AI
 * funnel narrative, the quarterly AI rejection-pattern narrative and the
 * weekly DPO exception digest. Runs DAILY — each digest decides for
 * itself whether its period needs generating (event-derived idempotency,
 * see {@link AiDigestService} and {@link RecruitmentDpoDigestService}),
 * so the normal Monday delivery self-heals on later weekdays after a
 * failure, and re-runs/concurrent instances are harmless.
 * <p>
 * One failing digest never stops the others. All side effects are gated
 * by their own {@code app_settings} flags (off = no-op per digest).
 * <p>
 * Scheduled daily at 06:30 UTC by {@code BatchScheduler} (after the
 * 05:45 GDPR sweep — the DPO digest reports post-sweep state — and
 * around the 06:00 morning briefs), gated by
 * {@code dk.trustworks.recruitment.digest.enabled}.
 */
@JBossLog
@Dependent
@Named("recruitmentDigestBatchlet")
public class RecruitmentDigestBatchlet extends MonitoredBatchlet {

    @Inject
    AiDigestService aiDigestService;

    @Inject
    RecruitmentDpoDigestService dpoDigestService;

    @Override
    protected String doProcess() {
        StringBuilder status = new StringBuilder("COMPLETED");
        status.append(' ').append(runSafely("weekly-funnel",
                () -> aiDigestService.runWeeklyFunnel().toString()));
        status.append(' ').append(runSafely("rejection-patterns",
                () -> aiDigestService.runRejectionPatterns().toString()));
        status.append(' ').append(runSafely("dpo-digest",
                () -> dpoDigestService.run().toString()));
        return status.toString();
    }

    private String runSafely(String label, java.util.function.Supplier<String> digest) {
        try {
            String summary = digest.get();
            log.infof("recruitment-digest %s: %s", label, summary);
            return summary;
        } catch (Exception e) {
            log.errorf(e, "recruitment-digest %s failed — continuing with the next digest "
                    + "(the next daily run retries)", label);
            return label + "[FAILED]";
        }
    }
}
