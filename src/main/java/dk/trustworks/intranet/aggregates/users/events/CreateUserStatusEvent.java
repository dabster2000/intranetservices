package dk.trustworks.intranet.aggregates.users.events;

import dk.trustworks.intranet.aggregates.sender.AggregateRootChangeEvent;
import dk.trustworks.intranet.userservice.model.UserStatus;
import io.vertx.core.json.JsonObject;
import lombok.Data;
import lombok.EqualsAndHashCode;
import lombok.NoArgsConstructor;

import jakarta.persistence.Entity;

import static dk.trustworks.intranet.messaging.emitters.enums.AggregateEventType.CREATE_USER_STATUS;

@Data
@NoArgsConstructor
@EqualsAndHashCode(onlyExplicitlyIncluded = true, callSuper = false)
@Entity
public class CreateUserStatusEvent extends AggregateRootChangeEvent {
    public CreateUserStatusEvent(String aggregateRootUUID, UserStatus userStatus) {
        super(aggregateRootUUID, CREATE_USER_STATUS, JsonObject.mapFrom(userStatus).encode());
    }
}
