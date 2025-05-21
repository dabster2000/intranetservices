package dk.trustworks.intranet.bi.processors;

import dk.trustworks.intranet.bi.events.SalaryChangedDayEvent;
import dk.trustworks.intranet.bi.services.SalaryCalculationService;
import io.smallrye.mutiny.Multi;
import io.smallrye.mutiny.Uni;
import io.smallrye.mutiny.infrastructure.Infrastructure;
import io.smallrye.mutiny.subscription.MultiEmitter;
import jakarta.annotation.PostConstruct;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import lombok.extern.jbosslog.JBossLog;
import org.jboss.logging.Logger;

import java.util.List;
import java.util.concurrent.atomic.AtomicReference;

@JBossLog
@ApplicationScoped
public class ReactiveSalaryUpdateProcessor {

    private static final Logger LOG = Logger.getLogger(ReactiveSalaryUpdateProcessor.class);
    // Buffer up to 1000 events (adjust this number as needed)
    private static final int BUFFER_SIZE = 1000;
    // Process events in batches of 50
    private static final int BATCH_SIZE = 50;

    private final AtomicReference<MultiEmitter<? super SalaryChangedDayEvent>> emitterRef = new AtomicReference<>();

    @Inject
    SalaryCalculationService salaryCalculationService;

    @PostConstruct
    public void init() {
        Multi.<SalaryChangedDayEvent>createFrom().<SalaryChangedDayEvent>emitter(emitterRef::set)
            // If events arrive too quickly, buffer up to BUFFER_SIZE
            .onOverflow().buffer(BUFFER_SIZE)
            // Group events into batches of BATCH_SIZE
            .group().intoLists().of(BATCH_SIZE)
            // Process each batch sequentially
            .onItem().transformToUniAndConcatenate(this::processBatch)
            .subscribe().with(
                unused -> LOG.debug("Batch processed successfully"),
                failure -> LOG.error("Error processing salary update events", failure)
            );
    }

    /**
     * Submit a single salary update event.
     */
    public void submitEvent(SalaryChangedDayEvent event) {
        MultiEmitter<? super SalaryChangedDayEvent> emitter = emitterRef.get();
        if (emitter != null) {
            emitter.emit(event);
        } else {
            LOG.error("Emitter not initialized; event dropped: " + event);
        }
    }

    /**
     * Process a batch of salary update events sequentially.
     */
    private Uni<Void> processBatch(List<SalaryChangedDayEvent> batch) {
        return Multi.createFrom().iterable(batch)
            // Process events one after the other
            .onItem().transformToUniAndConcatenate(this::processEvent)
            .collect().asList()
            .replaceWithVoid();
    }

    /**
     * Process a single event by invoking recalculateSalary.
     * We offload the execution to a worker thread so that blocking transactions
     * do not block the event loop.
     */
    private Uni<Void> processEvent(SalaryChangedDayEvent event) {
        return Uni.createFrom().voidItem()
            .invoke(() -> {
                salaryCalculationService.recalculateSalary(event);
            })
            .runSubscriptionOn(Infrastructure.getDefaultWorkerPool());
    }
}