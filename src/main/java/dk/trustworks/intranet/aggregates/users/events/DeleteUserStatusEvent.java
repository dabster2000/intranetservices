package dk.trustworks.intranet.aggregates.users.events;

import dk.trustworks.intranet.aggregates.sender.AggregateRootChangeEvent;
import lombok.Data;
import lombok.EqualsAndHashCode;
import lombok.NoArgsConstructor;

import jakarta.persistence.Entity;

import static dk.trustworks.intranet.messaging.emitters.enums.AggregateEventType.DELETE_USER_STATUS;

@Data
@NoArgsConstructor
@EqualsAndHashCode(onlyExplicitlyIncluded = true, callSuper = false)
@Entity
public class DeleteUserStatusEvent extends AggregateRootChangeEvent {
    public DeleteUserStatusEvent(String aggregateRootUUID, String userStatusUUID) {
        super(aggregateRootUUID, DELETE_USER_STATUS, userStatusUUID);
    }
}
