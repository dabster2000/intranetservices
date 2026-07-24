package dk.trustworks.intranet.recruitmentservice.jobs;

import dk.trustworks.intranet.batch.monitoring.MonitoredBatchlet;
import dk.trustworks.intranet.recruitmentservice.services.RecruitmentGdprService;
import jakarta.enterprise.context.Dependent;
import jakarta.inject.Inject;
import jakarta.inject.Named;
import lombok.extern.jbosslog.JBossLog;

/**
 * Nightly GDPR sweep (ATS expansion P19): expires stale consents, sends
 * consent-renewal emails and auto-anonymizes candidates past their
 * retention deadline — see {@link RecruitmentGdprService} for the three
 * sub-sweeps and their event-derived idempotency. A no-op while
 * {@code recruitment.gdpr.enabled} is off — <b>enabling that flag is the
 * moment automatic deletion starts</b> (plan §P19).
 * <p>
 * Scheduled once per day at 05:45 UTC by {@code BatchScheduler} (after
 * the 02:00–05:00 cleanup/refresh cluster, before the 07:00 SLA sweep so
 * a freshly-anonymized candidate can no longer be nudged about), gated by
 * {@code dk.trustworks.recruitment.gdpr-sweep.enabled}.
 */
@JBossLog
@Dependent
@Named("recruitmentGdprSweepBatchlet")
public class RecruitmentGdprSweepBatchlet extends MonitoredBatchlet {

    @Inject
    RecruitmentGdprService gdprService;

    @Override
    protected String doProcess() {
        RecruitmentGdprService.SweepSummary summary = gdprService.sweep();
        if (summary.consentsExpired() > 0 || summary.renewalsSent() > 0
                || summary.anonymized() > 0 || summary.failures() > 0) {
            log.infof("recruitment-gdpr-sweep: %s", summary);
        }
        return "COMPLETED " + summary;
    }
}
