package dk.trustworks.intranet.aggregates.client.events;

import dk.trustworks.intranet.aggregates.sender.AggregateRootChangeEvent;
import dk.trustworks.intranet.dao.crm.model.Client;
import io.vertx.core.json.JsonObject;
import lombok.Data;
import lombok.EqualsAndHashCode;
import lombok.NoArgsConstructor;

import javax.persistence.Entity;

import static dk.trustworks.intranet.messaging.emitters.enums.AggregateEventType.CREATE_CLIENT;

@Data
@NoArgsConstructor
@EqualsAndHashCode(onlyExplicitlyIncluded = true, callSuper = false)
@Entity
public class CreateClientEvent extends AggregateRootChangeEvent {

    public CreateClientEvent(String aggregateRootUUID, Client client) {
        super(aggregateRootUUID, CREATE_CLIENT, JsonObject.mapFrom(client).encode());
    }
}
