package dk.trustworks.intranet.messaging.consumers;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import dk.trustworks.intranet.bi.services.BudgetCalculatingExecutor;
import dk.trustworks.intranet.messaging.dto.DateRangeMap;
import dk.trustworks.intranet.messaging.dto.EventData;
import dk.trustworks.intranet.utils.DateUtils;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import io.opentelemetry.instrumentation.annotations.WithSpan;
import io.smallrye.reactive.messaging.kafka.api.IncomingKafkaRecordMetadata;
import io.smallrye.reactive.messaging.annotations.Blocking;
import lombok.extern.jbosslog.JBossLog;
import org.eclipse.microprofile.reactive.messaging.Acknowledgment;
import org.eclipse.microprofile.reactive.messaging.Incoming;
import org.eclipse.microprofile.reactive.messaging.Message;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import java.time.LocalDate;
import java.util.Optional;
import java.util.concurrent.CompletionStage;

@JBossLog
@ApplicationScoped
public class BudgetUpdateConsumer {

    private static final String CHANNEL = "budget-updates";

    @Inject
    BudgetCalculatingExecutor budgetCalculatingExecutor;

    @Inject
    MeterRegistry registry;

    @Inject
    dk.trustworks.intranet.config.FeatureFlags featureFlags;

    private final ObjectMapper mapper = new ObjectMapper();

    @Incoming(CHANNEL)
    @Acknowledgment(Acknowledgment.Strategy.MANUAL)
    @WithSpan("consumer.budget-updates")
    @Blocking
    public CompletionStage<Void> onMessage(Message<String> msg) {
        Timer timer = registry.timer("kafka.consumer.process", "channel", CHANNEL);
        var success = registry.counter("kafka.consumer.messages", "result", "success", "channel", CHANNEL);
        var errors = registry.counter("kafka.consumer.messages", "result", "error", "channel", CHANNEL);
        Timer.Sample sample = Timer.start(registry);
        long startNs = System.nanoTime();
        Optional<IncomingKafkaRecordMetadata> meta = msg.getMetadata(IncomingKafkaRecordMetadata.class);
        String topic = meta.map(IncomingKafkaRecordMetadata::getTopic).orElse(CHANNEL);
        int partition = meta.map(IncomingKafkaRecordMetadata::getPartition).orElse(-1);
        long offset = meta.map(IncomingKafkaRecordMetadata::getOffset).orElse(-1L);
        String key = meta.map(IncomingKafkaRecordMetadata::getKey).map(Object::toString).orElse(null);
        log.debugf("Received Kafka message channel=%s topic=%s partition=%d offset=%d key=%s", CHANNEL, topic, partition, offset, key);
        try {
            if (featureFlags.isKafkaConsumersShadow()) {
                long durMs = (System.nanoTime() - startNs) / 1_000_000;
                log.infof("Shadow skip channel=%s topic=%s partition=%d offset=%d key=%s durationMs=%d", CHANNEL, topic, partition, offset, key, durMs);
                success.increment();
                sample.stop(timer);
                return msg.ack();
            }
            String payload = msg.getPayload();
            EventData eventData = parseEventData(payload);
            if (eventData == null) {
                long durMs = (System.nanoTime() - startNs) / 1_000_000;
                log.warnf("No event data found; ack. channel=%s topic=%s partition=%d offset=%d key=%s durationMs=%d", CHANNEL, topic, partition, offset, key, durMs);
                sample.stop(timer);
                return msg.ack();
            }
            LocalDate start = DateUtils.dateIt(eventData.getAggregateDate());
            budgetCalculatingExecutor.calcBudgetsV2(new DateRangeMap(start, start.plusMonths(1)));
            long durMs = (System.nanoTime() - startNs) / 1_000_000;
            log.infof("Processed budget update topic=%s partition=%d offset=%d key=%s start=%s durationMs=%d", topic, partition, offset, key, start, durMs);
            success.increment();
            sample.stop(timer);
            return msg.ack();
        } catch (Exception e) {
            errors.increment();
            sample.stop(timer);
            log.errorf(e, "BudgetUpdateConsumer failed channel=%s topic=%s partition=%d offset=%d key=%s", CHANNEL, topic, partition, offset, key);
            return msg.nack(e);
        }
    }

    private EventData parseEventData(String json) throws Exception {
        JsonNode root = mapper.readTree(json);
        if (root.has("version")) {
            JsonNode responsePayloadNode = root.get("responsePayload");
            if (responsePayloadNode != null && !responsePayloadNode.isNull()) {
                return mapper.treeToValue(responsePayloadNode, EventData.class);
            } else return null;
        }
        return mapper.treeToValue(root, EventData.class);
    }
}
