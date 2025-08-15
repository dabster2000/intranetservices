package dk.trustworks.intranet.messaging.bridge;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import dk.trustworks.intranet.config.FeatureFlags;
import dk.trustworks.intranet.messaging.config.ConfigTopicMapper;
import dk.trustworks.intranet.messaging.dto.DomainEventEnvelope;
import dk.trustworks.intranet.messaging.dto.EventData;
import dk.trustworks.intranet.messaging.emitters.enums.AggregateEventType;
import dk.trustworks.intranet.messaging.producer.KafkaEventPublisher;
import dk.trustworks.intranet.utils.DateUtils;
import io.quarkus.vertx.ConsumeEvent;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import lombok.extern.jbosslog.JBossLog;

import java.time.LocalDate;

@JBossLog
@ApplicationScoped
public class ExternalEventBridge {

    @Inject
    FeatureFlags flags;

    @Inject
    KafkaEventPublisher publisher;

    @Inject
    ConfigTopicMapper topicMapper;

    private static final ObjectMapper mapper = new ObjectMapper();

    private boolean dispatcherEnabled() {
        return flags.isKafkaUseOutboxDispatcher();
    }

    // Per-event-type consumers
    @ConsumeEvent(value = "domain.events.CREATE_USER_STATUS", blocking = true)
    public void onCreateUserStatus(String envelopeJson) { if (dispatcherEnabled()) return; handleSimple(envelopeJson, AggregateEventType.CREATE_USER_STATUS); }

    @ConsumeEvent(value = "domain.events.DELETE_USER_STATUS", blocking = true)
    public void onDeleteUserStatus(String envelopeJson) { if (dispatcherEnabled()) return; handleSimple(envelopeJson, AggregateEventType.DELETE_USER_STATUS); }

    @ConsumeEvent(value = "domain.events.CREATE_USER_SALARY", blocking = true)
    public void onCreateUserSalary(String envelopeJson) { if (dispatcherEnabled()) return; handleSimple(envelopeJson, AggregateEventType.CREATE_USER_SALARY); }

    @ConsumeEvent(value = "domain.events.DELETE_USER_SALARY", blocking = true)
    public void onDeleteUserSalary(String envelopeJson) { if (dispatcherEnabled()) return; handleSimple(envelopeJson, AggregateEventType.DELETE_USER_SALARY); }

    @ConsumeEvent(value = "domain.events.UPDATE_WORK", blocking = true)
    public void onUpdateWork(String envelopeJson) { if (dispatcherEnabled()) return; handleSimple(envelopeJson, AggregateEventType.UPDATE_WORK); }

    @ConsumeEvent(value = "domain.events.MODIFY_CONTRACT_CONSULTANT", blocking = true)
    public void onModifyContractConsultant(String envelopeJson) {
        if (dispatcherEnabled()) return;
        if (!flags.isKafkaLiveProducerEnabled()) return;
        try {
            DomainEventEnvelope env = DomainEventEnvelope.fromJson(envelopeJson);
            // Monthly fanout
            JsonNode root = mapper.readTree(env.getPayload());
            String useruuid = env.getAggregateId();
            LocalDate start = LocalDate.parse(root.get("activeFrom").asText());
            LocalDate end = LocalDate.parse(root.get("activeTo").asText());
            LocalDate month = start.withDayOfMonth(1);
            int fanout = 0;
            String topic = topicMapper.topicForType(AggregateEventType.MODIFY_CONTRACT_CONSULTANT.name());
            if (topic == null) {
                log.debugf("ExternalEventBridge: no topic mapping for contract consultant updates");
                return;
            }
            while (month.isBefore(end.plusMonths(1).withDayOfMonth(1))) {
                sendToKafka(topic, useruuid, month);
                fanout++;
                month = month.plusMonths(1);
            }
            log.infof("Published contract.consultant fan-out events user=%s start=%s end=%s count=%d", useruuid, start, end, fanout);
        } catch (Exception e) {
            log.errorf(e, "ExternalEventBridge MODIFY_CONTRACT_CONSULTANT failed for envelope");
        }
    }

    private void handleSimple(String envelopeJson, AggregateEventType expectedType) {
        if (!flags.isKafkaLiveProducerEnabled()) return;
        try {
            DomainEventEnvelope env = DomainEventEnvelope.fromJson(envelopeJson);
            publishSimple(env, expectedType);
        } catch (Exception e) {
            log.errorf(e, "ExternalEventBridge %s failed", expectedType);
        }
    }

    private void publishSimple(DomainEventEnvelope env, AggregateEventType type) throws Exception {
        String topic = topicMapper.topicForType(type.name());
        if (topic == null) {
            log.debugf("ExternalEventBridge: no topic mapping for type=%s, event=%s", type, env.getEventId());
            return; // not an external type
        }
        String key = env.getAggregateId();
        String dateStr = deriveDate(env, type);
        EventData ed = new EventData(key, dateStr);
        String json = mapper.writeValueAsString(ed);
        log.infof("Publishing external event type=%s topic=%s key=%s date=%s", type, topic, key, dateStr);
        publisher.send(topic, key, json);
    }

    private String deriveDate(DomainEventEnvelope env, AggregateEventType type) {
        // Legacy parity where deletes fan out from company start
        if (type == AggregateEventType.DELETE_USER_STATUS) {
            return DateUtils.stringIt(DateUtils.getCompanyStartDate());
        }
        if (env.getEffectiveDate() != null) {
            return DateUtils.stringIt(env.getEffectiveDate());
        }
        return LocalDate.now().toString();
    }

    private void sendToKafka(String topic, String key, LocalDate date) throws Exception {
        EventData ed = new EventData(key, DateUtils.stringIt(date));
        String json = mapper.writeValueAsString(ed);
        publisher.send(topic, key, json);
    }
}
