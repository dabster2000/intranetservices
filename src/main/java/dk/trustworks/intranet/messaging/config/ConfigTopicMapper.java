package dk.trustworks.intranet.messaging.config;

import jakarta.annotation.PostConstruct;
import jakarta.enterprise.context.ApplicationScoped;
import lombok.extern.jbosslog.JBossLog;

import org.eclipse.microprofile.config.Config;
import org.eclipse.microprofile.config.ConfigProvider;

import java.util.HashMap;
import java.util.Map;

@JBossLog
@ApplicationScoped
public class ConfigTopicMapper {

    private final Map<String, String> typeToTopic = new HashMap<>();

    @PostConstruct
    void init() {
        Config config = ConfigProvider.getConfig();
        for (String name : config.getPropertyNames()) {
            if (name.startsWith("event-topics.")) {
                String suffix = name.substring("event-topics.".length());
                String eventType = suffix.toUpperCase();
                try {
                    String topic = config.getValue(name, String.class);
                    typeToTopic.put(eventType, topic);
                } catch (Exception e) {
                    log.warnf("Could not read topic mapping for %s: %s", name, e.getMessage());
                }
            }
        }
        log.infof("Loaded %d event topic mappings from configuration", typeToTopic.size());
    }

    public String topicForType(String eventType) {
        if (eventType == null) return null;
        String topic = typeToTopic.get(eventType);
        if (topic != null) return topic;
        return typeToTopic.get(eventType.toUpperCase());
    }
}
