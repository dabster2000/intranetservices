package dk.trustworks.intranet.messaging.outbox;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import dk.trustworks.intranet.config.FeatureFlags;
import dk.trustworks.intranet.messaging.config.ConfigTopicMapper;
import dk.trustworks.intranet.messaging.dto.DomainEventEnvelope;
import dk.trustworks.intranet.messaging.dto.EventData;
import dk.trustworks.intranet.messaging.emitters.enums.AggregateEventType;
import dk.trustworks.intranet.messaging.producer.KafkaEventPublisher;
import dk.trustworks.intranet.messaging.routing.EventAddress;
import dk.trustworks.intranet.utils.DateUtils;
import io.quarkus.narayana.jta.QuarkusTransaction;
import io.quarkus.scheduler.Scheduled;
import io.vertx.mutiny.core.eventbus.EventBus;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import lombok.extern.jbosslog.JBossLog;

import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;

@JBossLog
@ApplicationScoped
public class OutboxDispatcher {

    @Inject
    FeatureFlags flags;

    @Inject
    KafkaEventPublisher publisher;

    @Inject
    ConfigTopicMapper topicMapper;

    @Inject
    EventBus eventBus;

    private static final ObjectMapper mapper = new ObjectMapper();

    private final AtomicBoolean running = new AtomicBoolean(false);

    // Poll every 5 seconds by default
    @Scheduled(every = "5s")
    void dispatch() {
        if (!flags.isOutboxDispatcherEnabled()) return;
        if (!running.compareAndSet(false, true)) return; // prevent overlaps
        try {
            // Fetch a small batch of unprocessed events ordered by time
            var q = OutboxEvent.<OutboxEvent>find("processed = false order by occurredAt asc, id asc");
            List<OutboxEvent> batch = q.page(0, 100).list();
            if (batch.isEmpty()) return;
            log.infof("OutboxDispatcher found %d unprocessed events", batch.size());
            for (OutboxEvent evt : batch) {
                try {
                    processOne(evt);
                } catch (Exception e) {
                    log.errorf(e, "Failed to process OutboxEvent id=%s type=%s", evt.getId(), evt.getType());
                }
            }
        } finally {
            running.set(false);
        }
    }

    private void processOne(OutboxEvent evt) throws Exception {
        String raw = evt.getPayload();
        DomainEventEnvelope env;
        try {
            env = DomainEventEnvelope.fromJson(raw);
        } catch (Exception e) {
            env = adaptLegacyEnvelope(evt, raw);
        }
        // Fill missing critical fields from OutboxEvent if absent
        if (env.getEventType() == null) env.setEventType(evt.getType());
        if (env.getAggregateId() == null) env.setAggregateId(evt.getAggregateId());
        if (env.getOccurredAt() == null && evt.getOccurredAt() != null) {
            env.setOccurredAt(evt.getOccurredAt().atZone(ZoneOffset.UTC).toInstant());
        }
        if (env.getEventId() == null) env.setEventId(evt.getId());
        // Ensure payload present for legacy formats
        if (env.getPayload() == null && raw != null && !raw.isBlank()) {
            try {
                JsonNode rn = mapper.readTree(raw);
                if (rn.has("payload") && !rn.get("payload").isNull()) {
                    env.setPayload(rn.get("payload").toString());
                } else if (rn.has("eventContent")) {
                    JsonNode ec = rn.get("eventContent");
                    env.setPayload(ec.isNull() ? null : ec.toString());
                } else if (rn.isObject() || rn.isArray()) {
                    env.setPayload(rn.toString());
                }
            } catch (Exception ignore) {
                // leave payload null
            }
        }
        String eventType = env.getEventType();
        AggregateEventType type = null;
        try { type = AggregateEventType.valueOf(eventType); } catch (Exception ignored) {}

        // 1) External Kafka publish (if mapping exists and global Kafka producer enabled)
        if (flags.isKafkaLiveProducerEnabled()) {
            if (type == AggregateEventType.MODIFY_CONTRACT_CONSULTANT) {
                publishContractFanout(evt, env);
            } else {
                publishSimple(evt, env, type);
            }
        }

        // 2) Optional internal EventBus publish per-event-type address
        if (flags.isOutboxInternalPublishEnabled()) {
            String address = EventAddress.of(eventType);
            eventBus.publish(address, env.toJson());
        }

        // 3) Mark processed with idempotency key
        QuarkusTransaction.requiringNew().run(() -> {
            OutboxEvent.update("processed = true where id = ?1", evt.getId());
        });
    }

    private DomainEventEnvelope adaptLegacyEnvelope(OutboxEvent evt, String raw) {
        DomainEventEnvelope env = new DomainEventEnvelope();
        env.setEventId(evt.getId());
        env.setEventType(evt.getType());
        env.setAggregateType(null);
        env.setAggregateId(evt.getAggregateId());
        if (evt.getOccurredAt() != null) {
            env.setOccurredAt(evt.getOccurredAt().atZone(ZoneOffset.UTC).toInstant());
        }
        env.setVersion(1);
        try {
            if (raw != null && !raw.isBlank()) {
                JsonNode node = mapper.readTree(raw);
                // If legacy event wrapper: extract eventContent and actor
                if (node.has("eventContent")) {
                    JsonNode content = node.get("eventContent");
                    env.setPayload(content.isNull() ? null : content.toString());
                } else {
                    // If raw JSON is already a domain payload, keep it as payload
                    env.setPayload(node.toString());
                }
                if (node.has("eventUser") && !node.get("eventUser").isNull()) {
                    env.setActor(node.get("eventUser").asText());
                }
                // Aggregate id may be present as aggregateRootUUID in legacy
                if (env.getAggregateId() == null && node.has("aggregateRootUUID")) {
                    env.setAggregateId(node.get("aggregateRootUUID").asText());
                }
            }
        } catch (Exception ignored) {
            // If raw isn't JSON, just pass it through as payload
            env.setPayload(raw);
        }
        return env;
    }

    private void publishSimple(OutboxEvent evt, DomainEventEnvelope env, AggregateEventType type) throws Exception {
        String topic = topicMapper.topicForType(evt.getType());
        if (topic == null) {
            log.debugf("OutboxDispatcher: no topic mapping for type=%s", evt.getType());
            return;
        }
        String key = evt.getAggregateId();
        String dateStr = deriveDate(env, type);
        EventData ed = new EventData(key, dateStr);
        String json = mapper.writeValueAsString(ed);
        Map<String, String> headers = defaultHeaders(evt, env);
        headers.putIfAbsent("idempotency-key", evt.getId());
        log.infof("Outbox->Kafka type=%s topic=%s key=%s date=%s", evt.getType(), topic, key, dateStr);
        publisher.sendWithHeaders(topic, key, json, headers, true);
    }

    private void publishContractFanout(OutboxEvent evt, DomainEventEnvelope env) throws Exception {
        String topic = topicMapper.topicForType(evt.getType());
        if (topic == null) {
            log.debugf("OutboxDispatcher: no topic mapping for contract consultant updates");
            return;
        }
        JsonNode root = mapper.readTree(env.getPayload());
        String useruuid = env.getAggregateId();
        LocalDate start = LocalDate.parse(root.get("activeFrom").asText());
        LocalDate end = LocalDate.parse(root.get("activeTo").asText());
        LocalDate month = start.withDayOfMonth(1);
        int fanout = 0;
        while (month.isBefore(end.plusMonths(1).withDayOfMonth(1))) {
            EventData ed = new EventData(useruuid, DateUtils.stringIt(month));
            String json = mapper.writeValueAsString(ed);
            Map<String, String> headers = defaultHeaders(evt, env);
            headers.put("idempotency-key", evt.getId() + ":" + DateUtils.stringIt(month));
            publisher.sendWithHeaders(topic, useruuid, json, headers, true);
            fanout++;
            month = month.plusMonths(1);
        }
        log.infof("Outbox->Kafka fanout type=%s topic=%s key=%s count=%d", evt.getType(), topic, useruuid, fanout);
    }

    private Map<String, String> defaultHeaders(OutboxEvent evt, DomainEventEnvelope env) {
        Map<String, String> h = new HashMap<>();
        if (env.getEventId() != null) h.put("event-id", env.getEventId());
        if (env.getEventType() != null) h.put("event-type", env.getEventType());
        if (env.getAggregateId() != null) h.put("aggregate-id", env.getAggregateId());
        return h;
    }

    private String deriveDate(DomainEventEnvelope env, AggregateEventType type) {
        if (type == AggregateEventType.DELETE_USER_STATUS) {
            return DateUtils.stringIt(DateUtils.getCompanyStartDate());
        }
        if (env.getEffectiveDate() != null) {
            return DateUtils.stringIt(env.getEffectiveDate());
        }
        // fallback: occurredAt date
        return env.getOccurredAt() != null ? env.getOccurredAt().toString().substring(0, 10) : LocalDate.now().toString();
    }
}
