package dk.trustworks.intranet.aggregates.work.query;

import dk.trustworks.intranet.aggregates.sender.SNSEventSender;
import dk.trustworks.intranet.dao.workservice.model.Work;
import dk.trustworks.intranet.messaging.dto.DomainEventEnvelope;
import dk.trustworks.intranet.messaging.emitters.enums.AggregateEventType;
import io.quarkus.vertx.ConsumeEvent;
import io.vertx.core.json.JsonObject;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import lombok.extern.jbosslog.JBossLog;

import java.time.LocalDate;

import static dk.trustworks.intranet.messaging.emitters.AggregateMessageEmitter.WORK_EVENT;

@JBossLog
@ApplicationScoped
public class WorkEventHandler {

    @Inject
    SNSEventSender snsEventSender;

    @Inject
    dk.trustworks.intranet.config.FeatureFlags featureFlags;

    @ConsumeEvent(value = "domain.events.UPDATE_WORK", blocking = true)
    public void onUpdateWork(String envelopeJson) {
        DomainEventEnvelope env = DomainEventEnvelope.fromJson(envelopeJson);
        log.info("WorkEventHandler.onUpdateWork -> envelopeId = " + env.getEventId());
        updateWork(env);
    }

    private void updateWork(DomainEventEnvelope env) {
        String useruuid = env.getAggregateId();
        Work work = new JsonObject(env.getPayload()).mapTo(Work.class);
        LocalDate testDay = work.getRegistered();

        if (featureFlags.isSnsEnabled()) {
            snsEventSender.sendEvent(SNSEventSender.WorkUpdateTopic, useruuid, testDay);
        } else {
            log.debug("SNS disabled (feature.sns.enabled=false). Skipping SNS publish: WorkUpdateTopic");
        }
    }
}
