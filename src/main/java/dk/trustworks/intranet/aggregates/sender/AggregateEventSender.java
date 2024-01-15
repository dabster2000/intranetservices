package dk.trustworks.intranet.aggregates.sender;

import dk.trustworks.intranet.messaging.emitters.AggregateMessageEmitter;
import io.quarkus.narayana.jta.QuarkusTransaction;

import jakarta.enterprise.context.RequestScoped;
import jakarta.inject.Inject;

@RequestScoped
public class AggregateEventSender {

    @Inject
    AggregateMessageEmitter messageEmitter;

    public void handleEvent(AggregateRootChangeEvent event) {
        persistEvent(event);
        messageEmitter.sendAggregateEvent(event);
    }

    private void persistEvent(AggregateRootChangeEvent event) {
        QuarkusTransaction.requiringNew().run(event::persist);
    }
}
