package dk.trustworks.intranet.messaging.emitters;

import dk.trustworks.intranet.aggregates.budgets.events.SystemChangeEvent;
import org.eclipse.microprofile.reactive.messaging.Channel;
import org.eclipse.microprofile.reactive.messaging.Emitter;

import javax.enterprise.context.ApplicationScoped;

@ApplicationScoped
public class SystemMessageEmitter {

    public static final String SEND_BUDGET_UPDATE_EVENT = "send-budget-update-events";
    public static final String READ_BUDGET_UPDATE_EVENT = "budget-update-events";

    @Channel(SEND_BUDGET_UPDATE_EVENT)
    Emitter<SystemChangeEvent> budgetEventEmitter;

    public void sendAggregateEvent(SystemChangeEvent systemChangeEvent) {
        switch (systemChangeEvent.getEventType()) {
            case UPDATE_BUDGET -> budgetEventEmitter.send(systemChangeEvent);
        }
    }
}
