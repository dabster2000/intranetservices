package dk.trustworks.intranet.messaging.emitters;

import dk.trustworks.intranet.aggregates.sender.SystemChangeEvent;
import io.vertx.mutiny.core.eventbus.EventBus;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

@ApplicationScoped
public class SystemMessageEmitter {

    public static final String BUDGET_UPDATE_EVENT = "send-budget-update-events";
    //public static final String WORK_UPDATE_EVENT = "work-update-events";

    @Inject
    EventBus eventBus;

    public void sendAggregateEvent(SystemChangeEvent systemChangeEvent) {
        switch (systemChangeEvent.getEventType()) {
            case UPDATE_BUDGET -> eventBus.publish(BUDGET_UPDATE_EVENT, systemChangeEvent);
            //case UPDATE_WORK -> eventBus.publish(WORK_UPDATE_EVENT, systemChangeEvent);
        }
    }
}
