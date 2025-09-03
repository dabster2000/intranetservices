package dk.trustworks.intranet.aggregates.users.events;

import dk.trustworks.intranet.aggregates.sender.AggregateRootChangeEvent;
import dk.trustworks.intranet.domain.user.entity.UserStatus;
import io.vertx.core.json.JsonObject;
import lombok.Data;
import lombok.EqualsAndHashCode;
import lombok.NoArgsConstructor;

import jakarta.persistence.Entity;

import static dk.trustworks.intranet.messaging.emitters.enums.AggregateEventType.UPDATE_USER_STATUS;

@Data
@NoArgsConstructor
@EqualsAndHashCode(onlyExplicitlyIncluded = true, callSuper = false)
@Entity
public class UpdateUserStatusEvent extends AggregateRootChangeEvent {
    public UpdateUserStatusEvent(String aggregateRootUUID, UserStatus userStatus) {
        super(aggregateRootUUID, UPDATE_USER_STATUS, JsonObject.mapFrom(userStatus).encode());
        this.setEffectiveDate(userStatus.getStatusdate());
    }
}