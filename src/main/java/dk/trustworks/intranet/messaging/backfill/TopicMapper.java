package dk.trustworks.intranet.messaging.backfill;

import java.util.HashMap;
import java.util.Map;

/**
 * Maps OutboxEvent.type (AggregateEventType name) to Kafka topic names.
 */
public final class TopicMapper {

    private static final Map<String, String> TYPE_TO_TOPIC = new HashMap<>();

    static {
        // All BI-related topic mappings removed — recalculation is handled by
        // DB triggers + sp_incremental_bi_refresh (every 5 min).
        // Only expenses-created remains (managed via SmallRye reactive messaging, not this mapper).
    }

    private TopicMapper() {}

    public static String topicForType(String type) {
        return TYPE_TO_TOPIC.get(type);
    }
}
