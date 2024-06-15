package dk.trustworks.intranet.aggregates.work.query;

import dk.trustworks.intranet.aggregates.availability.jobs.AvailabilityCalculatingExecutor;
import dk.trustworks.intranet.aggregates.sender.SystemChangeEvent;
import dk.trustworks.intranet.messaging.emitters.enums.SystemEventType;
import io.quarkus.vertx.ConsumeEvent;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.context.control.ActivateRequestContext;
import jakarta.inject.Inject;
import lombok.extern.jbosslog.JBossLog;

import static dk.trustworks.intranet.messaging.emitters.SystemMessageEmitter.WORK_UPDATE_EVENT;

@JBossLog
@ApplicationScoped
public class WorkEventHandler {

    @Inject
    AvailabilityCalculatingExecutor availabilityCalculatingExecutor;

    @ActivateRequestContext
    @ConsumeEvent(value = WORK_UPDATE_EVENT, blocking = true)
    public void readConferenceEvent(SystemChangeEvent event) {
        SystemEventType type = event.getEventType();
        log.info("WorkEventHandler.readConferenceEvent type = " + type);
        availabilityCalculatingExecutor.createAvailabilityDocumentByUserAndDate(event);
    }

}
