package dk.trustworks.intranet.aggregates.users.events;

import dk.trustworks.intranet.aggregates.sender.AggregateRootChangeEvent;
import dk.trustworks.intranet.userservice.model.SalarySupplement;
import io.vertx.core.json.JsonObject;
import jakarta.persistence.Entity;
import lombok.Data;
import lombok.EqualsAndHashCode;
import lombok.NoArgsConstructor;
import lombok.ToString;

import static dk.trustworks.intranet.messaging.emitters.enums.AggregateEventType.CREATE_USER_SALARY;

@Data
@ToString(callSuper = true)
@NoArgsConstructor
@EqualsAndHashCode(onlyExplicitlyIncluded = true, callSuper = false)
@Entity
public class CreateSalarySupplementEvent extends AggregateRootChangeEvent {
    public CreateSalarySupplementEvent(String aggregateRootUUID, SalarySupplement salarySupplement) {
        super(aggregateRootUUID, CREATE_USER_SALARY, JsonObject.mapFrom(salarySupplement).encode());
    }
}
