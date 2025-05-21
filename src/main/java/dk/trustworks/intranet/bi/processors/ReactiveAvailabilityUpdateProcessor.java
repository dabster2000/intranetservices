package dk.trustworks.intranet.bi.processors;

import dk.trustworks.intranet.bi.events.AvailabilityUpdateDayEvent;
import dk.trustworks.intranet.bi.services.UserAvailabilityCalculatorService;
import io.smallrye.mutiny.Multi;
import io.smallrye.mutiny.Uni;
import io.smallrye.mutiny.infrastructure.Infrastructure;
import io.smallrye.mutiny.subscription.MultiEmitter;
import jakarta.annotation.PostConstruct;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.jboss.logging.Logger;

import java.util.List;
import java.util.concurrent.atomic.AtomicReference;

@ApplicationScoped
public class ReactiveAvailabilityUpdateProcessor {

    private static final Logger LOG = Logger.getLogger(ReactiveAvailabilityUpdateProcessor.class);
    private static final int BUFFER_SIZE = 1000;
    private static final int BATCH_SIZE = 50;

    private final AtomicReference<MultiEmitter<? super AvailabilityUpdateDayEvent>> emitterRef = new AtomicReference<>();

    @Inject
    UserAvailabilityCalculatorService availabilityCalculatorService;

    @PostConstruct
    public void init() {
        Multi.<AvailabilityUpdateDayEvent>createFrom().<AvailabilityUpdateDayEvent>emitter(emitterRef::set)
            .onOverflow().buffer(BUFFER_SIZE)
            .group().intoLists().of(BATCH_SIZE)
            .onItem().transformToUniAndConcatenate(this::processBatch)
            .subscribe().with(
                unused -> LOG.debug("Availability batch processed successfully"),
                failure -> LOG.error("Error processing availability update events", failure)
            );
    }

    public void submitEvent(AvailabilityUpdateDayEvent event) {
        MultiEmitter<? super AvailabilityUpdateDayEvent> emitter = emitterRef.get();
        if (emitter != null) {
            emitter.emit(event);
        } else {
            LOG.error("Emitter not initialized; event dropped: " + event);
        }
    }

    private Uni<Void> processBatch(List<AvailabilityUpdateDayEvent> batch) {
        return Multi.createFrom().iterable(batch)
            .onItem().transformToUniAndConcatenate(this::processEvent)
            .collect().asList()
            .replaceWithVoid();
    }

    private Uni<Void> processEvent(AvailabilityUpdateDayEvent event) {
        return Uni.createFrom().voidItem()
            .invoke(() -> {
                LOG.debugf("Processing availability update for user %s on day %s", event.useruuid(), event.testDay());
                // Call the calculator service method using the shared work list from AvailabilityData.
                availabilityCalculatorService.updateUserAvailabilityByDay(event.useruuid(), event.testDay(), event.availabilityData().workList());
            })
            .runSubscriptionOn(Infrastructure.getDefaultWorkerPool());
    }
}