package dk.trustworks.intranet.aggregates.client.query;

import dk.trustworks.intranet.aggregates.client.events.CreateClientEvent;
import dk.trustworks.intranet.aggregates.AggregateRootChangeEvent;
import dk.trustworks.intranet.dao.crm.model.Client;
import dk.trustworks.intranet.messaging.emitters.enums.AggregateEventType;
import io.vertx.core.json.JsonObject;
import org.eclipse.microprofile.reactive.messaging.Incoming;

import javax.enterprise.context.ApplicationScoped;
import javax.transaction.Transactional;

import static dk.trustworks.intranet.messaging.emitters.AggregateMessageEmitter.READ_CLIENT_EVENT;


@ApplicationScoped
public class ClientEventHandler {

    @Transactional
    @Incoming(READ_CLIENT_EVENT)
    public void readClientEvent(CreateClientEvent message) {
        AggregateEventType type = message.getEventType();
        switch (type) {
            case CREATE_CLIENT -> createClient(message);
        }
    }

    private void createClient(AggregateRootChangeEvent aggregateRootChangeEvent) {
        Client client = new JsonObject(aggregateRootChangeEvent.getEventContent()).mapTo(Client.class);
        client.persist();
    }
}
