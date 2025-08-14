package dk.trustworks.intranet.messaging.consumers.util;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import dk.trustworks.intranet.messaging.dto.EventData;

public class EventDataParser {
    private static final ObjectMapper mapper = new ObjectMapper();

    public static EventData parse(String json) throws Exception {
        JsonNode root = mapper.readTree(json);
        if (root.has("version")) {
            JsonNode responsePayloadNode = root.get("responsePayload");
            if (responsePayloadNode != null && !responsePayloadNode.isNull()) {
                return mapper.treeToValue(responsePayloadNode, EventData.class);
            } else {
                return null;
            }
        }
        return mapper.treeToValue(root, EventData.class);
    }
}
