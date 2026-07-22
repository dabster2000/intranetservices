package dk.trustworks.intranet.recruitmentservice.jobs;

import dk.trustworks.intranet.batch.monitoring.MonitoredBatchlet;
import dk.trustworks.intranet.recruitmentservice.events.RecruitmentReactor;
import jakarta.enterprise.context.Dependent;
import jakarta.enterprise.inject.Instance;
import jakarta.inject.Inject;
import jakarta.inject.Named;
import lombok.extern.jbosslog.JBossLog;

import java.util.ArrayList;
import java.util.List;

/**
 * Catch-up sweep for every registered {@link RecruitmentReactor}: delivers
 * all settled events between each reactor's watermark and the stream head,
 * in order. This is the reliability half of the event backbone — the live
 * EventBus path is lost on crash/deploy, the sweep is not (spec §3.2).
 * <p>
 * Idempotent by construction (watermark + per-event dedupe in the reactor
 * base), so a run on a draining ECS task during cutover is harmless. A
 * failing reactor is logged and skipped — it must never stall the other
 * reactors, and the next cycle retries it.
 * <p>
 * With no concrete reactors deployed (Phase 1) the sweep is a no-op.
 * Scheduled every 5 minutes by {@code BatchScheduler}, gated by
 * {@code dk.trustworks.recruitment.catchup.enabled}.
 */
@JBossLog
@Dependent
@Named("recruitmentEventCatchupBatchlet")
public class RecruitmentEventCatchupBatchlet extends MonitoredBatchlet {

    @Inject
    Instance<RecruitmentReactor> reactors;

    @Override
    protected String doProcess() {
        List<String> summaries = new ArrayList<>();
        for (RecruitmentReactor reactor : reactors) {
            try {
                RecruitmentReactor.CatchUpSummary summary = reactor.catchUp();
                summaries.add(summary.toString());
                if (summary.handled() > 0 || summary.skippedPoison() > 0 || summary.blocked()) {
                    log.infof("recruitment-event-catchup: %s", summary);
                }
            } catch (Exception e) {
                summaries.add(reactor.name() + "[FAILED]");
                log.errorf(e, "recruitment-event-catchup: reactor %s failed — continuing with remaining reactors",
                        reactor.name());
            }
        }
        return summaries.isEmpty() ? "COMPLETED (no reactors registered)" : "COMPLETED " + summaries;
    }
}
