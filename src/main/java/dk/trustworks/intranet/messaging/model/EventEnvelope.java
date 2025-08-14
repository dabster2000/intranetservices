package dk.trustworks.intranet.messaging.model;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.OffsetDateTime;
import java.util.Map;
import java.util.UUID;

@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class EventEnvelope {
    private String id;              // UUID v4/v7 acceptable for now
    private String type;            // e.g., CREATE_USER_STATUS
    private String aggregateType;   // e.g., User, Work, Contract
    private String aggregateId;     // aggregate root id
    private OffsetDateTime occurredAt; // UTC timestamp
    private String producer;        // service name
    private String schemaVersion;   // optional schema version
    private String correlationId;   // tracing
    private String causationId;     // tracing
    private Map<String, String> headers; // custom headers
    private String payload;         // JSON payload as string

    public static EventEnvelope basic(String type, String aggregateType, String aggregateId, String payload) {
        return EventEnvelope.builder()
                .id(UUID.randomUUID().toString())
                .type(type)
                .aggregateType(aggregateType)
                .aggregateId(aggregateId)
                .occurredAt(OffsetDateTime.now())
                .producer("intranetservices")
                .schemaVersion("1")
                .payload(payload)
                .build();
    }
}
