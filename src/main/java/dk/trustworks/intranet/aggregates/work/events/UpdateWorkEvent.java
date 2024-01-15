package dk.trustworks.intranet.aggregates.work.events;

import dk.trustworks.intranet.aggregates.sender.SystemChangeEvent;
import dk.trustworks.intranet.messaging.dto.UserDateMap;
import io.vertx.core.json.JsonObject;
import lombok.Data;
import lombok.EqualsAndHashCode;
import lombok.NoArgsConstructor;

import jakarta.persistence.Entity;

import static dk.trustworks.intranet.messaging.emitters.enums.SystemEventType.UPDATE_WORK;

@Data
@NoArgsConstructor
@EqualsAndHashCode(onlyExplicitlyIncluded = true, callSuper = false)
@Entity
public class UpdateWorkEvent extends SystemChangeEvent {

    public UpdateWorkEvent(UserDateMap item) {
        super(UPDATE_WORK, JsonObject.mapFrom(item).encode());
    }
}
