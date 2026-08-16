package dk.trustworks.intranet.utils.json;

import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.databind.DeserializationContext;
import com.fasterxml.jackson.databind.deser.std.StdDeserializer;
import dk.trustworks.intranet.utils.TemporalParams;

import java.io.IOException;
import java.time.LocalDateTime;
import java.time.format.DateTimeParseException;

/**
 * Reads a {@link UtcInstant} member, accepting BOTH the {@code ...Z} shape and the legacy
 * bare shape and resolving both to the same stored UTC value.
 *
 * <p>Needed because clients echo received values back (whole-entity PUTs, {@code If-Match}
 * concurrency tokens) and because during a rolling ECS deploy a client may hold either
 * shape. Unlike Jackson's stock deserializer this also accepts a numeric {@code +02:00}
 * offset rather than throwing.
 */
public class UtcInstantDeserializer extends StdDeserializer<LocalDateTime> {

    public UtcInstantDeserializer() {
        super(LocalDateTime.class);
    }

    @Override
    public LocalDateTime deserialize(JsonParser parser, DeserializationContext context) throws IOException {
        String raw = parser.getText();
        if (raw == null || raw.isBlank()) {
            return null;
        }
        try {
            return TemporalParams.parseUtcInstant(raw);
        } catch (DateTimeParseException e) {
            return (LocalDateTime) context.handleWeirdStringValue(LocalDateTime.class, raw,
                    "expected an ISO-8601 date-time, with or without a zone designator");
        }
    }
}
