package dk.trustworks.intranet.utils.json;

import com.fasterxml.jackson.core.JsonGenerator;
import com.fasterxml.jackson.databind.SerializerProvider;
import com.fasterxml.jackson.databind.ser.std.StdSerializer;

import java.io.IOException;
import java.time.LocalDateTime;
import java.time.ZoneOffset;

/**
 * Serializes a {@link LocalDateTime} that carries INSTANT semantics as an ISO-8601 instant
 * with an explicit {@code Z} designator, e.g. {@code 2026-08-16T16:27:00.925Z}.
 *
 * <p>{@code Instant.toString()} is {@code DateTimeFormatter.ISO_INSTANT}: it ALWAYS ends in
 * {@code 'Z'} — never a numeric {@code +00:00} — and emits the fractional second in groups
 * of three digits, or omits it when zero. Both properties are load-bearing downstream: the
 * frontend's interview work-arounds test {@code endsWith('Z')} and would produce
 * {@code Invalid Date} against {@code +00:00}, and the fraction is variable-length so no
 * consumer may slice by index.
 *
 * <p>Bound only through {@link UtcInstant}, deliberately NOT registered module-wide: the
 * bare wire shape carries two different semantics — UTC instants and Europe/Copenhagen
 * wall-clock times entered by a human — the split is per-FIELD, and a blanket change would
 * shift the wall-clock family in the wrong direction.
 */
public class UtcInstantSerializer extends StdSerializer<LocalDateTime> {

    public UtcInstantSerializer() {
        super(LocalDateTime.class);
    }

    @Override
    public void serialize(LocalDateTime value, JsonGenerator generator, SerializerProvider provider)
            throws IOException {
        generator.writeString(value.toInstant(ZoneOffset.UTC).toString());
    }
}
