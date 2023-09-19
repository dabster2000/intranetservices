package dk.trustworks.intranet.messaging.emitters;

import dk.trustworks.intranet.aggregates.AggregateRootChangeEvent;
import org.eclipse.microprofile.reactive.messaging.Channel;
import org.eclipse.microprofile.reactive.messaging.Emitter;

import javax.enterprise.context.ApplicationScoped;

@ApplicationScoped
public class AggregateMessageEmitter {


    public static final String SEND_BROWSER_EVENT = "send-browser-events";
    public static final String READ_BROWSER_EVENT = "browser-events";
    public static final String SEND_CLIENT_EVENT = "send-client-events";
    public static final String READ_CLIENT_EVENT = "client-events";
    public static final String SEND_USER_EVENT = "send-user-events";
    public static final String READ_USER_EVENT = "user-events";

    @Channel(SEND_CLIENT_EVENT)
    Emitter<AggregateRootChangeEvent> clientEventEmitter;
    @Channel(SEND_USER_EVENT)
    Emitter<AggregateRootChangeEvent> userEventEmitter;

    public void sendAggregateEvent(AggregateRootChangeEvent aggregateRootChangeEvent) {
        switch (aggregateRootChangeEvent.getEventType()) {
            case CREATE_CLIENT -> clientEventEmitter.send(aggregateRootChangeEvent);
            case CREATE_USER -> userEventEmitter.send(aggregateRootChangeEvent);
            case UPDATE_USER -> userEventEmitter.send(aggregateRootChangeEvent);
            case CREATE_USER_STATUS -> userEventEmitter.send(aggregateRootChangeEvent);
            case DELETE_USER_STATUS -> userEventEmitter.send(aggregateRootChangeEvent);
            case CREATE_USER_SALARY -> userEventEmitter.send(aggregateRootChangeEvent);
            case DELETE_USER_SALARY -> userEventEmitter.send(aggregateRootChangeEvent);
        }
    }

}
