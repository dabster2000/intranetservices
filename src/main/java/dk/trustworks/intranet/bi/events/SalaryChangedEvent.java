package dk.trustworks.intranet.bi.events;

import dk.trustworks.intranet.userservice.model.Salary;

public record SalaryChangedEvent(String rootuuid, String entityuuid, Salary entity) implements CreatedEvent {
}