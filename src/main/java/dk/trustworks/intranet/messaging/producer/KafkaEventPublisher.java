package dk.trustworks.intranet.messaging.producer;

import io.micrometer.core.instrument.MeterRegistry;
import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import lombok.extern.jbosslog.JBossLog;
import org.apache.kafka.clients.producer.KafkaProducer;
import org.apache.kafka.clients.producer.Producer;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.apache.kafka.common.serialization.StringSerializer;

import java.time.Duration;
import java.util.Properties;

@JBossLog
@ApplicationScoped
public class KafkaEventPublisher {

    private Producer<String, String> producer;

    @Inject
    MeterRegistry registry;

    @PostConstruct
    void init() {
        Properties props = new Properties();
        String bootstrap = System.getenv().getOrDefault("KAFKA_BOOTSTRAP_SERVERS", "localhost:9094");
        props.put("bootstrap.servers", bootstrap);
        props.put("key.serializer", StringSerializer.class.getName());
        props.put("value.serializer", StringSerializer.class.getName());
        props.put("enable.idempotence", "true");
        props.put("acks", "all");
        props.put("retries", 5);
        props.put("linger.ms", 5);
        props.put("max.in.flight.requests.per.connection", 5);
        producer = new KafkaProducer<>(props);
        log.infof("KafkaEventPublisher initialized with bootstrap.servers=%s", bootstrap);
    }

    public void send(String topic, String key, String value) {
        var success = registry.counter("kafka.producer.messages", "result", "success", "topic", topic);
        var errors = registry.counter("kafka.producer.messages", "result", "error", "topic", topic);
        try {
            log.debugf("Producing record topic=%s key=%s valueSize=%d", topic, key, value != null ? value.length() : -1);
            producer.send(new ProducerRecord<>(topic, key, value), (metadata, exception) -> {
                if (exception != null) {
                    errors.increment();
                    log.errorf(exception, "Failed to produce to %s key=%s", topic, key);
                } else {
                    success.increment();
                }
            });
        } catch (Exception e) {
            errors.increment();
            log.errorf(e, "Producer send threw exception for %s key=%s", topic, key);
            throw e;
        }
    }

    public void flush() {
        try {
            producer.flush();
        } catch (Exception e) {
            log.warn("Error during producer flush", e);
        }
    }

    @PreDestroy
    void close() {
        try {
            producer.flush();
            producer.close(Duration.ofSeconds(5));
        } catch (Exception e) {
            log.warn("Error closing Kafka producer", e);
        }
    }
}
