package dk.trustworks.intranet.bi.events;

public interface CreatedEvent {
    String rootuuid();
    String entityuuid();
    Object entity();
}