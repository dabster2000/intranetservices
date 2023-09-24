package dk.trustworks.intranet.aggregates.users.events;

import dk.trustworks.intranet.aggregates.AggregateRootChangeEvent;
import lombok.Data;
import lombok.EqualsAndHashCode;
import lombok.NoArgsConstructor;
import lombok.ToString;

import javax.persistence.Entity;

import static dk.trustworks.intranet.messaging.emitters.enums.AggregateEventType.DELETE_USER_SALARY;

@Data
@ToString(callSuper = true)
@NoArgsConstructor
@EqualsAndHashCode(onlyExplicitlyIncluded = true, callSuper = true)
@Entity
public class DeleteSalaryEvent extends AggregateRootChangeEvent {
    public DeleteSalaryEvent(String aggregateRootUUID, String salaryUUID) {
        super(aggregateRootUUID, DELETE_USER_SALARY, salaryUUID);
    }
}
