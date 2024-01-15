package dk.trustworks.intranet.aggregates.users.events;

import dk.trustworks.intranet.aggregates.sender.AggregateRootChangeEvent;
import dk.trustworks.intranet.userservice.model.User;
import io.vertx.core.json.JsonObject;
import lombok.Data;
import lombok.EqualsAndHashCode;
import lombok.NoArgsConstructor;

import jakarta.persistence.Entity;

import static dk.trustworks.intranet.messaging.emitters.enums.AggregateEventType.CREATE_USER;

@Data
@NoArgsConstructor
@EqualsAndHashCode(onlyExplicitlyIncluded = true, callSuper = false)
@Entity
public class CreateUserEvent extends AggregateRootChangeEvent {

    public CreateUserEvent(String aggregateRootUUID, User user) {
        super(aggregateRootUUID, CREATE_USER, JsonObject.mapFrom(user).encode());
    }
}
