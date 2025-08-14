package dk.trustworks.intranet.messaging.backfill;

import java.util.HashMap;
import java.util.Map;

/**
 * Maps OutboxEvent.type (AggregateEventType name) to Kafka topic names.
 */
public final class TopicMapper {

    private static final Map<String, String> TYPE_TO_TOPIC = new HashMap<>();

    static {
        // User status changes
        TYPE_TO_TOPIC.put("CREATE_USER_STATUS", "user.status.updates");
        TYPE_TO_TOPIC.put("DELETE_USER_STATUS", "user.status.updates");

        // Salary changes
        TYPE_TO_TOPIC.put("CREATE_USER_SALARY", "user.salary.updates");
        TYPE_TO_TOPIC.put("DELETE_USER_SALARY", "user.salary.updates");

        // Work updates
        TYPE_TO_TOPIC.put("UPDATE_WORK", "work.updates");

        // Contract consultant updates
        TYPE_TO_TOPIC.put("MODIFY_CONTRACT_CONSULTANT", "contract.consultant.updates");

        // If in future a dedicated budget update type is introduced, map it here:
        // TYPE_TO_TOPIC.put("BUDGET_UPDATE", "budget.updates");
    }

    private TopicMapper() {}

    public static String topicForType(String type) {
        return TYPE_TO_TOPIC.get(type);
    }
}
