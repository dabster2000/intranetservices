package dk.trustworks.intranet.messaging.bridge;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import dk.trustworks.intranet.aggregates.sender.AggregateRootChangeEvent;
import dk.trustworks.intranet.config.FeatureFlags;
import dk.trustworks.intranet.messaging.backfill.TopicMapper;
import dk.trustworks.intranet.messaging.dto.EventData;
import dk.trustworks.intranet.messaging.emitters.AggregateMessageEmitter;
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

    private static final ObjectMapper mapper = new ObjectMapper();

    @ConsumeEvent(value = AggregateMessageEmitter.USER_EVENT, blocking = true)
    public void onUserEvent(AggregateRootChangeEvent event) {
        if (!flags.isKafkaLiveProducerEnabled()) return;
        try {
            AggregateEventType type = event.getEventType();
            switch (type) {
                case CREATE_USER_STATUS, DELETE_USER_STATUS -> publishSimple(event, type);
                case CREATE_USER_SALARY, DELETE_USER_SALARY -> publishSimple(event, type);
                default -> log.debugf("ExternalEventBridge USER_EVENT: ignoring type=%s for event=%s", type, event.getUuid());
            }
        } catch (Exception e) {
            log.errorf(e, "ExternalEventBridge USER_EVENT failed for %s", event.getUuid());
        }
    }

    @ConsumeEvent(value = AggregateMessageEmitter.WORK_EVENT, blocking = true)
    public void onWorkEvent(AggregateRootChangeEvent event) {
        if (!flags.isKafkaLiveProducerEnabled()) return;
        try {
            if (event.getEventType() == AggregateEventType.UPDATE_WORK) {
                publishSimple(event, AggregateEventType.UPDATE_WORK);
            }
        } catch (Exception e) {
            log.errorf(e, "ExternalEventBridge WORK_EVENT failed for %s", event.getUuid());
        }
    }

    @ConsumeEvent(value = AggregateMessageEmitter.CONTRACT_EVENT, blocking = true)
    public void onContractEvent(AggregateRootChangeEvent event) {
        if (!flags.isKafkaLiveProducerEnabled()) return;
        try {
            if (event.getEventType() == AggregateEventType.MODIFY_CONTRACT_CONSULTANT) {
                // Monthly fanout
                JsonNode root = mapper.readTree(event.getEventContent());
                String useruuid = event.getAggregateRootUUID();
                LocalDate start = LocalDate.parse(root.get("activeFrom").asText());
                LocalDate end = LocalDate.parse(root.get("activeTo").asText());
                LocalDate month = start.withDayOfMonth(1);
                int fanout = 0;
                while (month.isBefore(end.plusMonths(1).withDayOfMonth(1))) {
                    sendToKafka("contract.consultant.updates", useruuid, month);
                    fanout++;
                    month = month.plusMonths(1);
                }
                log.infof("Published contract.consultant fan-out events user=%s start=%s end=%s count=%d", useruuid, start, end, fanout);
            }
        } catch (Exception e) {
            log.errorf(e, "ExternalEventBridge CONTRACT_EVENT failed for %s", event.getUuid());
        }
    }

    private void publishSimple(AggregateRootChangeEvent event, AggregateEventType type) throws Exception {
        String topic = TopicMapper.topicForType(type.name());
        if (topic == null) {
            log.debugf("ExternalEventBridge: no topic mapping for type=%s, event=%s", type, event.getUuid());
            return; // not an external type
        }
        String key = event.getAggregateRootUUID();
        String dateStr = deriveDate(event, type);
        EventData ed = new EventData(key, dateStr);
        String json = mapper.writeValueAsString(ed);
        log.infof("Publishing external event type=%s topic=%s key=%s date=%s", type, topic, key, dateStr);
        publisher.send(topic, key, json);
    }

    private String deriveDate(AggregateRootChangeEvent event, AggregateEventType type) throws Exception {
        // Legacy parity where deletes fan out from company start
        if (type == AggregateEventType.DELETE_USER_STATUS) {
            return DateUtils.stringIt(DateUtils.getCompanyStartDate());
        }
        if (event.getEventContent() == null || event.getEventContent().isBlank()) {
            return LocalDate.now().toString();
        }
        JsonNode root = mapper.readTree(event.getEventContent());
        // Try common fields first
        String[] fields = new String[]{"aggregateDate", "statusdate", "activefrom", "registered", "date", "documentDate", "activeFrom"};
        for (String f : fields) {
            if (root.has(f) && !root.get(f).isNull()) {
                String v = root.get(f).asText();
                if (v != null && !v.isBlank()) return v;
            }
        }
        return LocalDate.now().toString();
    }

    private void sendToKafka(String topic, String key, LocalDate date) throws Exception {
        EventData ed = new EventData(key, DateUtils.stringIt(date));
        String json = mapper.writeValueAsString(ed);
        publisher.send(topic, key, json);
    }
}
