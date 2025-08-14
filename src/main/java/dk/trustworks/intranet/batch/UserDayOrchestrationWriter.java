package dk.trustworks.intranet.batch;

import dk.trustworks.intranet.batch.model.UserDay;
import dk.trustworks.intranet.bi.services.BudgetCalculatingExecutor;
import dk.trustworks.intranet.bi.services.UserAvailabilityCalculatorService;
import dk.trustworks.intranet.bi.services.WorkAggregateService;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import jakarta.batch.api.BatchProperty;
import jakarta.batch.api.chunk.ItemWriter;
import jakarta.batch.runtime.context.StepContext;
import jakarta.enterprise.context.Dependent;
import jakarta.inject.Inject;
import jakarta.inject.Named;
import lombok.extern.jbosslog.JBossLog;

import java.io.Serializable;
import java.time.Duration;
import java.util.List;
import java.util.Properties;

@JBossLog
@Named("userDayOrchestrationWriter")
@Dependent
public class UserDayOrchestrationWriter implements ItemWriter {

    @Inject UserAvailabilityCalculatorService availabilityService;
    @Inject WorkAggregateService workAggregateService;
    @Inject BudgetCalculatingExecutor budgetCalculatingExecutor;
    @Inject MeterRegistry registry;
    @Inject StepContext stepContext; // optional

    @Inject @BatchProperty(name = "delayMs")
    String delayMsStr;

    @Inject @BatchProperty(name = "partitionId")
    String partitionId;

    private long processed;
    private long errors;
    private long startNs;
    private long delayMs;

    @Override
    public void open(Serializable checkpoint) {
        processed = 0; errors = 0; startNs = System.nanoTime();
        try { delayMs = (delayMsStr == null || delayMsStr.isBlank()) ? 0L : Long.parseLong(delayMsStr); }
        catch (Exception e) { delayMs = 0L; }
        String pid = (partitionId == null || partitionId.isBlank()) ? "?" : partitionId;
        log.infof("UserDayOrchestrationWriter opened: partition=%s delayMs=%d", pid, delayMs);
    }

    @Override
    public void writeItems(List<Object> items) {
        Timer timer = registry.timer("batch.budget_agg.user_day.seconds");
        var success = registry.counter("batch.budget_agg.user_day", "result", "success");
        var error = registry.counter("batch.budget_agg.user_day", "result", "error");

        for (Object o : items) {
            if (!(o instanceof UserDay ud)) continue;
            Timer.Sample sample = Timer.start(registry);
            try {
                availabilityService.updateUserAvailabilityByDay(ud.getUseruuid(), ud.getDay());
                workAggregateService.recalculateWork(ud.getUseruuid(), ud.getDay());
                budgetCalculatingExecutor.recalculateUserDailyBudgets(ud.getUseruuid(), ud.getDay());
                processed++; success.increment();
            } catch (Exception ex) {
                errors++; error.increment();
                log.errorf(ex, "UserDayOrchestrationWriter failed user=%s day=%s", ud.getUseruuid(), ud.getDay());
            } finally {
                sample.stop(timer);
            }
            if (delayMs > 0) {
                try { Thread.sleep(delayMs); } catch (InterruptedException ie) { Thread.currentThread().interrupt(); }
            }
            if ((processed + errors) % 100 == 0) {
                long durMs = (System.nanoTime() - startNs) / 1_000_000;
                String pid = (partitionId == null || partitionId.isBlank()) ? "?" : partitionId;
                log.infof("Partition %s progress: processed=%d errors=%d elapsedMs=%d", pid, processed, errors, durMs);
            }
        }
    }

    @Override
    public Serializable checkpointInfo() { return null; }

    @Override
    public void close() {
        long durMs = (System.nanoTime() - startNs) / 1_000_000;
        String pid = (partitionId == null || partitionId.isBlank()) ? "?" : partitionId;
        log.infof("Partition %s completed: processed=%d errors=%d elapsedMs=%d", pid, processed, errors, durMs);
    }
}