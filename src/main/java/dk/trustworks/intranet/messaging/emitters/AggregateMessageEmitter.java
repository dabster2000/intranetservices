package dk.trustworks.intranet.messaging.emitters;

import dk.trustworks.intranet.aggregates.sender.AggregateRootChangeEvent;
import dk.trustworks.intranet.messaging.dto.DomainEventEnvelope;
import dk.trustworks.intranet.messaging.routing.EventRoutingRegistry;
import io.vertx.mutiny.core.eventbus.EventBus;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

@ApplicationScoped
public class AggregateMessageEmitter {

    public static final String BROWSER_EVENT = "send-browser-events";
    public static final String CLIENT_EVENT = "send-client-events";
    public static final String CONTRACT_EVENT = "send-contract-events";
    public static final String WORK_EVENT = "send-work-events";
    public static final String USER_EVENT = "send-user-events";
    public static final String CONFERENCE_EVENT = "send-conference-events";

    @Inject
    EventBus eventBus;

    @Inject
    EventRoutingRegistry routingRegistry;

    public void sendAggregateEvent(AggregateRootChangeEvent aggregateRootChangeEvent) {
        DomainEventEnvelope envelope = DomainEventEnvelope.fromAggregateEvent(aggregateRootChangeEvent);
        String envelopeJson = envelope.toJson();
        // 1) Per-event-type address publication (preferred)
        String perTypeAddress = "domain.events." + envelope.getEventType();
        eventBus.publish(perTypeAddress, envelopeJson);
        // 2) Backward-compatible coarse channel publication
        String address = routingRegistry
                .resolveAddress(envelope.getEventType(), aggregateRootChangeEvent.getClass().getName())
                .orElse(USER_EVENT);
        eventBus.publish(address, envelopeJson);
    }
}
