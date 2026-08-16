package dk.trustworks.intranet.utils;

import org.junit.jupiter.api.Test;

import java.time.LocalDateTime;
import java.time.format.DateTimeParseException;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * Pins the reader used for {@code If-Match} preconditions and other echoed temporal
 * values. Every shape a client can legitimately hold must resolve to the SAME stored
 * instant, because the wire shape of a field changes when it becomes
 * {@link dk.trustworks.intranet.utils.json.UtcInstant @UtcInstant} and differs between
 * task revisions during a rolling deploy.
 */
class TemporalParamsTest {

    private static final LocalDateTime STORED =
            LocalDateTime.of(2026, 8, 16, 16, 27, 0, 925_000_000);

    @Test
    void bare_shape_is_read_verbatim() {
        assertEquals(STORED, TemporalParams.parseUtcInstant("2026-08-16T16:27:00.925"));
    }

    @Test
    void z_designator_resolves_to_the_same_instant() {
        assertEquals(STORED, TemporalParams.parseUtcInstant("2026-08-16T16:27:00.925Z"));
    }

    @Test
    void numeric_offset_resolves_to_the_same_instant() {
        // Jackson's stock LocalDateTime deserializer THROWS on this shape; we must not.
        assertEquals(STORED, TemporalParams.parseUtcInstant("2026-08-16T18:27:00.925+02:00"));
        assertEquals(STORED, TemporalParams.parseUtcInstant("2026-08-16T18:27:00.925+0200"));
        assertEquals(STORED, TemporalParams.parseUtcInstant("2026-08-16T14:27:00.925-02:00"));
    }

    @Test
    void a_value_without_fractional_seconds_is_accepted() {
        assertEquals(LocalDateTime.of(2026, 6, 8, 9, 23, 25),
                TemporalParams.parseUtcInstant("2026-06-08T09:23:25"));
        assertEquals(LocalDateTime.of(2026, 6, 8, 9, 23, 25),
                TemporalParams.parseUtcInstant("2026-06-08T09:23:25Z"));
    }

    @Test
    void surrounding_whitespace_is_tolerated() {
        assertEquals(STORED, TemporalParams.parseUtcInstant("  2026-08-16T16:27:00.925Z  "));
    }

    @Test
    void the_date_half_hyphens_are_never_mistaken_for_a_negative_offset() {
        // Guards the zone-designator probe: it must anchor past the 'T'.
        assertEquals(LocalDateTime.of(2026, 8, 16, 16, 27),
                TemporalParams.parseUtcInstant("2026-08-16T16:27:00"));
    }

    @Test
    void malformed_text_throws_rather_than_degrading() {
        // The whole point of the If-Match hardening: a present-but-broken precondition
        // must fail loudly, not silently disable lost-update protection.
        assertThrows(DateTimeParseException.class,
                () -> TemporalParams.parseUtcInstant("not-a-date"));
        assertThrows(DateTimeParseException.class,
                () -> TemporalParams.parseUtcInstant("2026-08-16"));
    }

    @Test
    void orNull_variant_absorbs_absent_and_malformed_input() {
        assertNull(TemporalParams.parseUtcInstantOrNull(null));
        assertNull(TemporalParams.parseUtcInstantOrNull(""));
        assertNull(TemporalParams.parseUtcInstantOrNull("   "));
        assertNull(TemporalParams.parseUtcInstantOrNull("not-a-date"));
        assertEquals(STORED, TemporalParams.parseUtcInstantOrNull("2026-08-16T16:27:00.925Z"));
    }
}
