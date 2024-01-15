package dk.trustworks.intranet.aggregates.users.events;

import dk.trustworks.intranet.aggregates.sender.AggregateRootChangeEvent;
import dk.trustworks.intranet.userservice.model.User;
import io.vertx.core.json.JsonObject;
import lombok.Data;
import lombok.EqualsAndHashCode;
import lombok.NoArgsConstructor;

import jakarta.persistence.Entity;

import static dk.trustworks.intranet.messaging.emitters.enums.AggregateEventType.UPDATE_USER;

@Data
@NoArgsConstructor
@EqualsAndHashCode(onlyExplicitlyIncluded = true, callSuper = false)
@Entity
public class UpdateUserEvent extends AggregateRootChangeEvent {

    public UpdateUserEvent(String aggregateRootUUID, User user) {
        super(aggregateRootUUID, UPDATE_USER, JsonObject.mapFrom(user).encode());
    }
}
