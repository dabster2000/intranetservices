package dk.trustworks.intranet.bi.events;

public record AvailabilityCreatedEvent(String rootuuid, String entityuuid, Object entity) implements CreatedEvent {

}