package dk.trustworks.intranet.messaging.dto;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.databind.ObjectMapper;
import dk.trustworks.intranet.aggregates.sender.AggregateRootChangeEvent;
import dk.trustworks.intranet.messaging.emitters.enums.AggregateEventType;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;

@Data
@NoArgsConstructor
@JsonInclude(JsonInclude.Include.NON_NULL)
@com.fasterxml.jackson.annotation.JsonIgnoreProperties(ignoreUnknown = true)
public class DomainEventEnvelope {

    private String eventId; // UUID
    private String eventType; // string name of event type
    private String aggregateType; // class simple name
    private String aggregateId; // aggregate root UUID
    private Instant occurredAt; // UTC timestamp
    private LocalDate effectiveDate; // optional
    private Integer version; // default 1
    private String correlationId; // optional
    private String causationId; // optional
    private String actor; // event user / actor
    private String payload; // JSON string (as-is)
    private String schemaRef; // optional

    private static final ObjectMapper MAPPER = new ObjectMapper()
                .registerModule(new com.fasterxml.jackson.datatype.jsr310.JavaTimeModule())
                .configure(com.fasterxml.jackson.databind.DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false)
                .disable(com.fasterxml.jackson.databind.SerializationFeature.WRITE_DATES_AS_TIMESTAMPS);

    public static DomainEventEnvelope fromAggregateEvent(AggregateRootChangeEvent event) {
        DomainEventEnvelope env = new DomainEventEnvelope();
        env.setEventId(UUID.randomUUID().toString());
        AggregateEventType type = event.getEventType();
        env.setEventType(type != null ? type.name() : null);
        env.setAggregateType(event.getClass().getSimpleName());
        env.setAggregateId(event.getAggregateRootUUID());
        env.setOccurredAt(Instant.now());
        // effectiveDate unknown at this layer unless embedded in payload; keep null for now
        env.setVersion(1);
        env.setCorrelationId(null);
        env.setCausationId(null);
        env.setActor(event.getEventUser());
        env.setPayload(event.getEventContent());
        env.setSchemaRef(null);
        return env;
    }

    public String toJson() {
        try {
            return MAPPER.writeValueAsString(this);
        } catch (Exception e) {
            throw new RuntimeException("Failed to serialize DomainEventEnvelope", e);
        }
    }

    public static DomainEventEnvelope fromJson(String json) {
        try {
            return MAPPER.readValue(json, DomainEventEnvelope.class);
        } catch (Exception e) {
            throw new RuntimeException("Failed to deserialize DomainEventEnvelope", e);
        }
    }
}
