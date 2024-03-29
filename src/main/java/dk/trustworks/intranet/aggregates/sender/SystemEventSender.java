package dk.trustworks.intranet.aggregates.sender;

import dk.trustworks.intranet.messaging.emitters.SystemMessageEmitter;
import io.quarkus.narayana.jta.QuarkusTransaction;

import jakarta.enterprise.context.RequestScoped;
import jakarta.inject.Inject;

@RequestScoped
public class SystemEventSender {

    @Inject
    SystemMessageEmitter messageEmitter;

    public void handleEvent(SystemChangeEvent event) {
        persistEvent(event);
        messageEmitter.sendAggregateEvent(event);
    }

    private void persistEvent(SystemChangeEvent event) {
        QuarkusTransaction.requiringNew().run(event::persist);
    }
}
