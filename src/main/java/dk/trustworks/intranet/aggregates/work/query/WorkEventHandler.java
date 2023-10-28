package dk.trustworks.intranet.aggregates.work.query;

import dk.trustworks.intranet.aggregates.sender.SystemChangeEvent;
import dk.trustworks.intranet.messaging.emitters.enums.SystemEventType;
import io.quarkus.vertx.ConsumeEvent;
import lombok.extern.jbosslog.JBossLog;

import javax.enterprise.context.ApplicationScoped;
import javax.enterprise.context.control.ActivateRequestContext;

import static dk.trustworks.intranet.messaging.emitters.SystemMessageEmitter.WORK_UPDATE_EVENT;

@JBossLog
@ApplicationScoped
public class WorkEventHandler {


    @ConsumeEvent(value = WORK_UPDATE_EVENT, blocking = true)
    @ActivateRequestContext
    public void readConferenceEvent(SystemChangeEvent event) {
        SystemEventType type = event.getEventType();

    }

}
