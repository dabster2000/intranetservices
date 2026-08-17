package dk.trustworks.intranet.utils;

import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeFormatterBuilder;
import java.time.format.DateTimeParseException;
import java.util.regex.Pattern;

/**
 * Readers for ISO-8601 date-times that arrive as text and denote a UTC INSTANT.
 *
 * <p>Business {@code LocalDateTime} values in this codebase are UTC instants: the container
 * JVM runs UTC (no {@code TZ} anywhere in infra/ or the Dockerfiles) and the RDS server
 * timezone is UTC. Historically they serialized as bare {@code ISO_LOCAL_DATE_TIME} with no
 * zone, which ECMAScript parses as BROWSER-LOCAL — two hours in the past in Copenhagen
 * summer time. Fields annotated
 * {@link dk.trustworks.intranet.utils.json.UtcInstant @UtcInstant} emit a {@code Z}
 * designator instead.
 *
 * <p>Because both shapes coexist — during a rolling deploy, and permanently for fields not
 * yet annotated — every reader of such a value must accept either. That is what this class
 * is for: a value echoed back by a client (an {@code If-Match} concurrency token, a request
 * body, a query param) may or may not carry a zone, and both must resolve to one instant.
 *
 * <p>Note that Jackson's stock {@code LocalDateTimeDeserializer} is <em>not</em> a
 * substitute: it silently strips a trailing {@code Z} but throws on a numeric
 * {@code +02:00} offset.
 */
public final class TemporalParams {

    /** A trailing {@code Z}/{@code z}, or a {@code ±HH:MM} / {@code ±HHMM} offset. */
    private static final Pattern ZONE_DESIGNATOR = Pattern.compile("(?:[Zz]|[+-]\\d{2}:?\\d{2})$");

    /**
     * Accepts every offset spelling {@link #ZONE_DESIGNATOR} recognises. Stock
     * {@code ISO_OFFSET_DATE_TIME} handles {@code Z} and {@code +02:00} but rejects the
     * equally valid basic form {@code +0200}, so the detector and the parser would
     * disagree and a {@code +0200} value would be flagged as zoned and then fail to parse.
     */
    private static final DateTimeFormatter OFFSET_DATE_TIME = new DateTimeFormatterBuilder()
            .append(DateTimeFormatter.ISO_LOCAL_DATE_TIME)
            .optionalStart().appendOffset("+HH:MM", "Z").optionalEnd()
            .optionalStart().appendOffset("+HHMM", "Z").optionalEnd()
            .toFormatter();

    private TemporalParams() {
    }

    /**
     * Parses an ISO-8601 date-time that MAY carry a zone designator and returns the
     * equivalent UTC wall-clock {@link LocalDateTime} — i.e. the value as it is stored.
     *
     * <p>{@code 2026-08-16T16:27:00.925}, {@code 2026-08-16T16:27:00.925Z} and
     * {@code 2026-08-16T18:27:00.925+02:00} all return {@code 2026-08-16T16:27:00.925}.
     *
     * @throws DateTimeParseException when the text is not an ISO-8601 date-time
     */
    public static LocalDateTime parseUtcInstant(String value) {
        String text = value.trim();
        if (hasZoneDesignator(text)) {
            return OffsetDateTime.parse(text, OFFSET_DATE_TIME)
                    .withOffsetSameInstant(ZoneOffset.UTC)
                    .toLocalDateTime();
        }
        return LocalDateTime.parse(text, DateTimeFormatter.ISO_LOCAL_DATE_TIME);
    }

    /** As {@link #parseUtcInstant(String)}, but {@code null} for absent/unparseable text. */
    public static LocalDateTime parseUtcInstantOrNull(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        try {
            return parseUtcInstant(value);
        } catch (DateTimeParseException e) {
            return null;
        }
    }

    /**
     * True when a zone designator follows the time part. Anchored past the {@code T} so the
     * {@code -} separators in the date half can never be mistaken for a negative offset.
     */
    private static boolean hasZoneDesignator(String text) {
        int t = text.indexOf('T');
        return t >= 0 && ZONE_DESIGNATOR.matcher(text.substring(t)).find();
    }
}
