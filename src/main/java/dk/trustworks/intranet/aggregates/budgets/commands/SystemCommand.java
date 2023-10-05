package dk.trustworks.intranet.aggregates.budgets.commands;

import dk.trustworks.intranet.aggregates.budgets.events.SystemChangeEvent;
import dk.trustworks.intranet.messaging.emitters.SystemMessageEmitter;
import io.quarkus.narayana.jta.QuarkusTransaction;

import javax.enterprise.context.RequestScoped;
import javax.inject.Inject;

@RequestScoped
public class SystemCommand {

    @Inject
    SystemMessageEmitter messageEmitter;

    public void handleEvent(SystemChangeEvent event) {
        persistEvent(event);
        messageEmitter.sendAggregateEvent(event);
    }

    private void persistEvent(SystemChangeEvent event) {
        QuarkusTransaction.begin();
        event.persist();
        QuarkusTransaction.commit();
    }
}
