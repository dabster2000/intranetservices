package dk.trustworks.intranet.bi.events;

import dk.trustworks.intranet.domain.user.entity.Salary;

public record SalaryChangedEvent(String rootuuid, String entityuuid, Salary entity) implements CreatedEvent {
}