package dk.trustworks.intranet.aggregates.sender;

import dk.trustworks.intranet.messaging.emitters.AggregateMessageEmitter;
import io.quarkus.narayana.jta.QuarkusTransaction;

import jakarta.enterprise.context.RequestScoped;
import jakarta.inject.Inject;
import jakarta.transaction.Status;
import jakarta.transaction.Synchronization;
import jakarta.transaction.TransactionSynchronizationRegistry;

@RequestScoped
public class AggregateEventSender {

    @Inject
    AggregateMessageEmitter messageEmitter;

    @Inject
    TransactionSynchronizationRegistry txSyncRegistry;

    public void handleEvent(AggregateRootChangeEvent event) {
        persistEvent(event);
        publishAfterCommit(event);
    }

    /**
     * Defer EventBus publish until after the current transaction commits.
     * This ensures async handlers see committed data and avoids deadlocks
     * between the triggering transaction and the handler's transaction.
     */
    private void publishAfterCommit(AggregateRootChangeEvent event) {
        try {
            txSyncRegistry.registerInterposedSynchronization(new Synchronization() {
                @Override
                public void beforeCompletion() {}

                @Override
                public void afterCompletion(int status) {
                    if (status == Status.STATUS_COMMITTED) {
                        messageEmitter.sendAggregateEvent(event);
                    }
                }
            });
        } catch (Exception e) {
            // No active transaction — publish immediately (safe: no lock contention)
            messageEmitter.sendAggregateEvent(event);
        }
    }

    private void persistEvent(AggregateRootChangeEvent event) {
        QuarkusTransaction.requiringNew().run(() -> {
            event.persist();
        });
    }
}
