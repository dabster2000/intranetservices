package dk.trustworks.intranet.bi.dispatcher;

import dk.trustworks.intranet.bi.events.CreatedEvent;
import dk.trustworks.intranet.bi.handlers.CreatedEventHandler;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.event.ObservesAsync;
import jakarta.enterprise.inject.Instance;
import jakarta.inject.Inject;
import lombok.extern.jbosslog.JBossLog;

@JBossLog
@ApplicationScoped
public class CreationEventDispatcher {

    @Inject
    Instance<CreatedEventHandler<?>> handlers;

    public void onCreatedEvent(@ObservesAsync CreatedEvent event) {
        log.infof("CreationEventDispatcher.onCreatedEvent: %s", event);
        for (CreatedEventHandler<?> handler : handlers) {
            if (handler.supports(event)) {
                handleEvent(handler, event);
            }
        }
    }

    // Helper method to cast the event to the handler's expected type
    @SuppressWarnings("unchecked")
    private <T extends CreatedEvent> void handleEvent(CreatedEventHandler<T> handler, CreatedEvent event) {
        handler.handle((T) event); // safe because supports(event) returned true
    }
}