package dk.trustworks.intranet.messaging.emitters;

import dk.trustworks.intranet.aggregates.sender.AggregateRootChangeEvent;
import io.vertx.mutiny.core.eventbus.EventBus;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

@ApplicationScoped
public class AggregateMessageEmitter {

    public static final String BROWSER_EVENT = "send-browser-events";
    public static final String CLIENT_EVENT = "send-client-events";
    public static final String USER_EVENT = "send-user-events";
    public static final String CONFERENCE_EVENT = "send-conference-events";

    @Inject
    EventBus eventBus;

    public void sendAggregateEvent(AggregateRootChangeEvent aggregateRootChangeEvent) {
        switch (aggregateRootChangeEvent.getEventType()) {
            case CREATE_CLIENT -> eventBus.publish(CLIENT_EVENT, aggregateRootChangeEvent);
            case CREATE_USER -> eventBus.publish(USER_EVENT, aggregateRootChangeEvent);
            case UPDATE_USER -> eventBus.publish(USER_EVENT, aggregateRootChangeEvent);
            case CREATE_USER_STATUS -> eventBus.publish(USER_EVENT, aggregateRootChangeEvent);
            case DELETE_USER_STATUS -> eventBus.publish(USER_EVENT, aggregateRootChangeEvent);
            case CREATE_USER_SALARY -> eventBus.publish(USER_EVENT, aggregateRootChangeEvent);
            case DELETE_USER_SALARY -> eventBus.publish(USER_EVENT, aggregateRootChangeEvent);
            case CREATE_CONFERENCE_PARTICIPANT -> eventBus.publish(CONFERENCE_EVENT, aggregateRootChangeEvent);
            case UPDATE_CONFERENCE_PARTICIPANT -> eventBus.publish(CONFERENCE_EVENT, aggregateRootChangeEvent);
            case CHANGE_CONFERENCE_PARTICIPANT_PHASE -> eventBus.publish(CONFERENCE_EVENT, aggregateRootChangeEvent);
            case CREATE_BANK_INFO -> eventBus.publish(USER_EVENT, aggregateRootChangeEvent);
        }
    }
}
