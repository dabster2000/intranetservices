package dk.trustworks.intranet.aggregates.work.events;

import dk.trustworks.intranet.aggregates.sender.AggregateRootChangeEvent;
import dk.trustworks.intranet.dao.workservice.model.Work;
import io.vertx.core.json.JsonObject;
import jakarta.persistence.Entity;
import lombok.Data;
import lombok.EqualsAndHashCode;
import lombok.NoArgsConstructor;

import static dk.trustworks.intranet.messaging.emitters.enums.AggregateEventType.UPDATE_WORK;

@Data
@NoArgsConstructor
@EqualsAndHashCode(onlyExplicitlyIncluded = true, callSuper = false)
@Entity
public class UpdateWorkEvent extends AggregateRootChangeEvent {

    public UpdateWorkEvent(String aggregateRootUUID, Work work) {
        super(aggregateRootUUID, UPDATE_WORK, JsonObject.mapFrom(work).encode());
    }
}
