package dk.trustworks.intranet.aggregates.users.events;

import dk.trustworks.intranet.aggregates.AggregateRootChangeEvent;
import lombok.Data;
import lombok.EqualsAndHashCode;
import lombok.NoArgsConstructor;

import javax.persistence.Entity;

import static dk.trustworks.intranet.messaging.emitters.enums.AggregateEventType.CREATE_USER_SALARY;

@Data
@NoArgsConstructor
@EqualsAndHashCode(onlyExplicitlyIncluded = true, callSuper = false)
@Entity
public class DeleteSalaryEvent extends AggregateRootChangeEvent {
    public DeleteSalaryEvent(String aggregateRootUUID, String salaryUUID) {
        super(aggregateRootUUID, CREATE_USER_SALARY, salaryUUID);
    }
}
