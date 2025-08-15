package dk.trustworks.intranet.messaging.routing;

import dk.trustworks.intranet.messaging.emitters.AggregateMessageEmitter;
import jakarta.annotation.PostConstruct;
import jakarta.enterprise.context.ApplicationScoped;
import lombok.extern.jbosslog.JBossLog;

import java.util.HashMap;
import java.util.Map;
import java.util.Optional;

@JBossLog
@ApplicationScoped
public class EventRoutingRegistry {

    private final Map<String, String> byEventType = new HashMap<>();

    @PostConstruct
    void init() {
        // Mirror previous switch routing in AggregateMessageEmitter
        byEventType.put("CREATE_CLIENT", AggregateMessageEmitter.CLIENT_EVENT);
        byEventType.put("MODIFY_CONTRACT_CONSULTANT", AggregateMessageEmitter.CONTRACT_EVENT);
        byEventType.put("UPDATE_WORK", AggregateMessageEmitter.WORK_EVENT);

        byEventType.put("CREATE_USER", AggregateMessageEmitter.USER_EVENT);
        byEventType.put("UPDATE_USER", AggregateMessageEmitter.USER_EVENT);
        byEventType.put("CREATE_USER_STATUS", AggregateMessageEmitter.USER_EVENT);
        byEventType.put("DELETE_USER_STATUS", AggregateMessageEmitter.USER_EVENT);
        byEventType.put("CREATE_USER_SALARY", AggregateMessageEmitter.USER_EVENT);
        byEventType.put("DELETE_USER_SALARY", AggregateMessageEmitter.USER_EVENT);
        byEventType.put("CREATE_BANK_INFO", AggregateMessageEmitter.USER_EVENT);

        byEventType.put("CREATE_CONFERENCE_PARTICIPANT", AggregateMessageEmitter.CONFERENCE_EVENT);
        byEventType.put("UPDATE_CONFERENCE_PARTICIPANT", AggregateMessageEmitter.CONFERENCE_EVENT);
        byEventType.put("CHANGE_CONFERENCE_PARTICIPANT_PHASE", AggregateMessageEmitter.CONFERENCE_EVENT);
    }

    public Optional<String> resolveAddress(String eventType, String aggregateClassName) {
        if (eventType != null) {
            String addr = byEventType.get(eventType);
            if (addr != null) return Optional.of(addr);
        }
        // Fallback: try derive by aggregate class/package naming if possible
        if (aggregateClassName != null) {
            String lower = aggregateClassName.toLowerCase();
            if (lower.contains("users")) return Optional.of(AggregateMessageEmitter.USER_EVENT);
            if (lower.contains("work")) return Optional.of(AggregateMessageEmitter.WORK_EVENT);
            if (lower.contains("contract")) return Optional.of(AggregateMessageEmitter.CONTRACT_EVENT);
            if (lower.contains("conference")) return Optional.of(AggregateMessageEmitter.CONFERENCE_EVENT);
            if (lower.contains("client")) return Optional.of(AggregateMessageEmitter.CLIENT_EVENT);
        }
        log.warnf("No routing mapping for eventType=%s aggregateClass=%s. Defaulting to USER_EVENT.", eventType, aggregateClassName);
        return Optional.of(AggregateMessageEmitter.USER_EVENT);
    }
}
