package dk.trustworks.intranet.recruitmentservice.jobs;

import dk.trustworks.intranet.batch.monitoring.MonitoredBatchlet;
import dk.trustworks.intranet.recruitmentservice.events.RecruitmentReactor;
import dk.trustworks.intranet.recruitmentservice.events.RecruitmentReactorDeadLetterService;
import jakarta.enterprise.context.Dependent;
import jakarta.enterprise.inject.Instance;
import jakarta.inject.Inject;
import jakarta.inject.Named;
import lombok.extern.jbosslog.JBossLog;

import java.util.ArrayList;
import java.util.List;
import java.util.stream.Collectors;

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

    @Inject
    RecruitmentReactorDeadLetterService deadLetters;

    @Override
    protected String doProcess() {
        List<String> summaries = new ArrayList<>();
        int skippedThisRun = 0;
        for (RecruitmentReactor reactor : reactors) {
            try {
                RecruitmentReactor.CatchUpSummary summary = reactor.catchUp();
                summaries.add(summary.toString());
                skippedThisRun += summary.skippedPoison();
                if (summary.handled() > 0 || summary.skippedPoison() > 0 || summary.blocked()) {
                    log.infof("recruitment-event-catchup: %s", summary);
                }
            } catch (Exception e) {
                summaries.add(reactor.name() + "[FAILED]");
                log.errorf(e, "recruitment-event-catchup: reactor %s failed — continuing with remaining reactors",
                        reactor.name());
            }
        }
        alertDeadLetters(skippedThisRun);
        return summaries.isEmpty() ? "COMPLETED (no reactors registered)" : "COMPLETED " + summaries;
    }

    /**
     * Surfaces permanently-skipped events instead of letting them sit in one
     * ERROR line that scrolls away — the recruitment sibling of
     * {@code ECONOMICS_UPLOAD_TERMINAL_FAILED}.
     *
     * <p>The token {@code RECRUITMENT_REACTOR_DEAD_LETTER} feeds the
     * CloudWatch metric filter created by
     * {@code scripts/setup-recruitment-reactor-alarms.sh}; the alarm stays
     * red for as long as any dead letter is OPEN. The line is ERROR on the
     * run where an event is newly dead-lettered (a new, actionable event)
     * and a WARN heartbeat on every later run, so log-based error monitoring
     * sees each one exactly once.
     *
     * <p>Resolution: {@code GET /recruitment/reactors/dead-letters}, then
     * either {@code .../{reactor}/{seq}/replay} once the cause is fixed, or
     * {@code .../{reactor}/{seq}/abandon} when the side effect is moot or
     * was already done by hand.
     *
     * <p>Never throws: a failure to read the dead-letter table must not turn
     * a healthy sweep into a failed batch job.
     */
    private void alertDeadLetters(int skippedThisRun) {
        try {
            long open = deadLetters.countOpen();
            if (open == 0) {
                return;
            }
            String detail = deadLetters.listOpen(20).stream()
                    .map(d -> d.getReactorName() + " seq=" + d.getEventSeq()
                            + " attempts=" + d.getAttempts() + " cause=" + d.getErrorMessage())
                    .collect(Collectors.joining("; "));
            if (skippedThisRun > 0) {
                log.errorf("RECRUITMENT_REACTOR_DEAD_LETTER: %d event(s) permanently skipped, %d newly this run. "
                                + "These will NEVER be delivered automatically — replay via "
                                + "POST /recruitment/reactors/dead-letters/{reactor}/{seq}/replay "
                                + "once the cause is fixed, or abandon them. [%s]",
                        open, skippedThisRun, detail);
            } else {
                log.warnf("RECRUITMENT_REACTOR_DEAD_LETTER: %d event(s) remain permanently skipped "
                        + "and need manual resolution. [%s]", open, detail);
            }
        } catch (Exception e) {
            log.errorf(e, "recruitment-event-catchup: dead-letter alarm check failed");
        }
    }
}
