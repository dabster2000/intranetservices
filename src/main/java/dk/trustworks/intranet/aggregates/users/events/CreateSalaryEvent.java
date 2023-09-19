package dk.trustworks.intranet.aggregates.users.events;

import dk.trustworks.intranet.aggregates.AggregateRootChangeEvent;
import dk.trustworks.intranet.userservice.model.Salary;
import io.vertx.core.json.JsonObject;
import lombok.Data;
import lombok.EqualsAndHashCode;
import lombok.NoArgsConstructor;

import javax.persistence.Entity;

import static dk.trustworks.intranet.messaging.emitters.enums.AggregateEventType.CREATE_USER_SALARY;

@Data
@NoArgsConstructor
@EqualsAndHashCode(onlyExplicitlyIncluded = true, callSuper = false)
@Entity
public class CreateSalaryEvent extends AggregateRootChangeEvent {
    public CreateSalaryEvent(String aggregateRootUUID, Salary salary) {
        super(aggregateRootUUID, CREATE_USER_SALARY, JsonObject.mapFrom(salary).encode());
    }
}
