package dk.trustworks.intranet.aggregates.work.query;

import dk.trustworks.intranet.messaging.dto.DomainEventEnvelope;
import io.quarkus.vertx.ConsumeEvent;
import jakarta.enterprise.context.ApplicationScoped;
import lombok.extern.jbosslog.JBossLog;

@JBossLog
@ApplicationScoped
public class WorkEventHandler {

    @ConsumeEvent(value = "domain.events.UPDATE_WORK", blocking = true)
    public void onUpdateWork(String envelopeJson) {
        DomainEventEnvelope env = DomainEventEnvelope.fromJson(envelopeJson);
        log.info("WorkEventHandler.onUpdateWork -> envelopeId = " + env.getEventId());
        updateWork(env);
    }

    private void updateWork(DomainEventEnvelope env) {
    }
}
