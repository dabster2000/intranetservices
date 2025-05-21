package dk.trustworks.intranet.bi.handlers;

import dk.trustworks.intranet.bi.events.CreatedEvent;

public interface CreatedEventHandler<T extends CreatedEvent> {
    boolean supports(CreatedEvent event);  // returns true if this handler can process the given event
    void handle(T event);
}