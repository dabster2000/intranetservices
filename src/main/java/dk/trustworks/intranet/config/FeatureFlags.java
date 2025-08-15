package dk.trustworks.intranet.config;

import jakarta.enterprise.context.ApplicationScoped;
import org.eclipse.microprofile.config.inject.ConfigProperty;

@ApplicationScoped
public class FeatureFlags {

    @ConfigProperty(name = "feature.sns.enabled", defaultValue = "true")
    boolean snsEnabled;

    @ConfigProperty(name = "feature.kafka.live-producer.enabled", defaultValue = "false")
    boolean kafkaLiveProducerEnabled;

    @ConfigProperty(name = "feature.kafka.consumers.shadow", defaultValue = "true")
    boolean kafkaConsumersShadow;

    // Phase 5: Outbox dispatcher controls
    @ConfigProperty(name = "feature.outbox.dispatcher.enabled", defaultValue = "true")
    boolean outboxDispatcherEnabled;

    // If true, external Kafka publication is driven by OutboxDispatcher (and ExternalEventBridge is disabled)
    @ConfigProperty(name = "feature.kafka.use-outbox-dispatcher", defaultValue = "true")
    boolean kafkaUseOutboxDispatcher;

    // If true, OutboxDispatcher will also publish internally to the Vert.x EventBus per event-type address
    @ConfigProperty(name = "feature.outbox.internal-publish.enabled", defaultValue = "false")
    boolean outboxInternalPublishEnabled;

    public boolean isSnsEnabled() {
        return snsEnabled;
    }

    public boolean isKafkaLiveProducerEnabled() {
        return kafkaLiveProducerEnabled;
    }

    public boolean isKafkaConsumersShadow() {
        return kafkaConsumersShadow;
    }

    public boolean isOutboxDispatcherEnabled() {
        return outboxDispatcherEnabled;
    }

    public boolean isKafkaUseOutboxDispatcher() {
        return kafkaUseOutboxDispatcher;
    }

    public boolean isOutboxInternalPublishEnabled() {
        return outboxInternalPublishEnabled;
    }
}
