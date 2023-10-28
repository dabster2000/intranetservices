package dk.trustworks.intranet.aggregates.sender;

import dk.trustworks.intranet.messaging.emitters.AggregateMessageEmitter;
import io.quarkus.narayana.jta.QuarkusTransaction;

import javax.enterprise.context.RequestScoped;
import javax.inject.Inject;

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
