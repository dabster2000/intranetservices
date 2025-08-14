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

    public boolean isSnsEnabled() {
        return snsEnabled;
    }

    public boolean isKafkaLiveProducerEnabled() {
        return kafkaLiveProducerEnabled;
    }

    public boolean isKafkaConsumersShadow() {
        return kafkaConsumersShadow;
    }
}
