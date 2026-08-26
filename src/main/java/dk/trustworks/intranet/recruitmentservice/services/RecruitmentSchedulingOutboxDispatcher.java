package dk.trustworks.intranet.recruitmentservice.services;

import dk.trustworks.intranet.recruitmentservice.model.RecruitmentSchedulingOutbox;
import dk.trustworks.intranet.recruitmentservice.model.enums.SchedulingOutboxAction;
import dk.trustworks.intranet.recruitmentservice.model.enums.SchedulingOutboxStatus;
import dk.trustworks.intranet.scheduling.SchedulerShutdownGuard;
import io.quarkus.narayana.jta.QuarkusTransaction;
import io.quarkus.scheduler.Scheduled;
import jakarta.annotation.PostConstruct;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.inject.Any;
import jakarta.enterprise.inject.Instance;
import jakarta.inject.Inject;
import lombok.extern.jbosslog.JBossLog;

import java.time.LocalDateTime;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;

/**
 * The Method B outbox dispatcher sweep (plan §8.3): every minute, claim
 * due rows atomically and hand them to the registered
 * {@link SchedulingOutboxExecutor}s. External calls run OUTSIDE any
 * transaction; bookkeeping (complete / retry-with-backoff / dead-letter)
 * runs in its own short transactions, so a deploy mid-action costs at
 * most one replay — which the executors' own idempotency absorbs.
 * <p>
 * An action with no registered executor (a later phase's action deployed
 * dark) is left untouched: not claimed, not failed — it simply waits for
 * the code that owns it. {@code ConcurrentExecution.SKIP} guards within
 * the JVM; the atomic claim guards across instances (blue/green).
 */
@JBossLog
@ApplicationScoped
public class RecruitmentSchedulingOutboxDispatcher {

    /** Rows examined per sweep — bounds one sweep's worst-case work. */
    static final int BATCH_SIZE = 50;

    @Inject
    RecruitmentSchedulingFeatureFlag methodBFlag;

    @Inject
    SchedulingOutboxService outboxService;

    @Inject
    @Any
    Instance<SchedulingOutboxExecutor> executorBeans;

    private Map<SchedulingOutboxAction, SchedulingOutboxExecutor> executors;

    @PostConstruct
    void buildRegistry() {
        Map<SchedulingOutboxAction, SchedulingOutboxExecutor> byAction =
                new EnumMap<>(SchedulingOutboxAction.class);
        for (SchedulingOutboxExecutor executor : executorBeans) {
            SchedulingOutboxExecutor previous = byAction.put(executor.action(), executor);
            if (previous != null) {
                throw new IllegalStateException(
                        "Duplicate scheduling outbox executor for " + executor.action());
            }
        }
        this.executors = byAction;
    }

    @Scheduled(every = "1m", identity = "recruitment-scheduling-outbox",
            concurrentExecution = Scheduled.ConcurrentExecution.SKIP,
            skipExecutionIf = SchedulerShutdownGuard.class)
    void dispatchTimer() {
        if (!methodBFlag.isMethodBEnabled()) {
            return;
        }
        dispatchDue();
    }

    /** One sweep. Package-visible for tests and manual ops runs. */
    public int dispatchDue() {
        LocalDateTime now = LocalDateTime.now();
        List<RecruitmentSchedulingOutbox> due = QuarkusTransaction.requiringNew()
                .call(() -> outboxService.due(now, BATCH_SIZE));
        int executed = 0;
        for (RecruitmentSchedulingOutbox row : due) {
            SchedulingOutboxExecutor executor = executors.get(row.getAction());
            if (executor == null) {
                log.debugf("Scheduling outbox: no executor registered for %s — leaving row %s pending",
                        row.getAction(), row.getUuid());
                continue;
            }
            if (!QuarkusTransaction.requiringNew()
                    .call(() -> outboxService.claim(row.getUuid(), LocalDateTime.now()))) {
                continue; // another instance owns it
            }
            executed++;
            try {
                executor.execute(row);
                QuarkusTransaction.requiringNew()
                        .run(() -> outboxService.complete(row.getUuid(), LocalDateTime.now()));
            } catch (Exception e) {
                String error = e.getClass().getSimpleName()
                        + (e.getMessage() != null ? ": " + e.getMessage() : "");
                SchedulingOutboxStatus outcome = QuarkusTransaction.requiringNew()
                        .call(() -> outboxService.recordFailure(row.getUuid(), error,
                                LocalDateTime.now()));
                if (outcome == SchedulingOutboxStatus.FAILED) {
                    log.errorf("Scheduling outbox action %s (%s) DEAD-LETTERED after %d attempts: %s",
                            row.getAction(), row.getUuid(), row.getAttemptCount(), error);
                } else {
                    log.warnf("Scheduling outbox action %s (%s) failed (attempt %d) — will retry: %s",
                            row.getAction(), row.getUuid(), row.getAttemptCount(), error);
                }
            }
        }
        return executed;
    }
}
