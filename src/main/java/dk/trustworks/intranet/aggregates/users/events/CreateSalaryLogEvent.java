package dk.trustworks.intranet.aggregates.users.events;

import dk.trustworks.intranet.aggregates.sender.AggregateRootChangeEvent;
import dk.trustworks.intranet.domain.user.entity.Salary;
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
public class CreateSalaryLogEvent extends AggregateRootChangeEvent {
    public CreateSalaryLogEvent(String aggregateRootUUID, Object salary) {
        super(aggregateRootUUID, CREATE_USER_SALARY, JsonObject.mapFrom(salary).encode());
    }
    public CreateSalaryLogEvent(String aggregateRootUUID, Salary salary) {
        super(aggregateRootUUID, CREATE_USER_SALARY, JsonObject.mapFrom(salary).encode());
        this.setEffectiveDate(salary.getActivefrom());
    }
}
