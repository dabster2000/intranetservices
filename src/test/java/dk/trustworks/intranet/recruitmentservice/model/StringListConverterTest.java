package dk.trustworks.intranet.recruitmentservice.model;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The empty-vs-null contract (P21 sample-import finding): an EMPTY list
 * must serialize to {@code '[]'}, never NULL — the importer creates
 * interview rows with no interviewers (Airtable never recorded them) and
 * {@code recruitment_interviews.interviewer_uuids} is NOT NULL. A null
 * list stays NULL (nullable columns like tags keep their semantics).
 */
class StringListConverterTest {

    private final StringListConverter converter = new StringListConverter();

    @Test
    void emptyList_serializesToEmptyJsonArray_notNull() {
        assertEquals("[]", converter.convertToDatabaseColumn(List.of()));
    }

    @Test
    void nullList_staysNull() {
        assertNull(converter.convertToDatabaseColumn(null));
    }

    @Test
    void roundTrip_emptyAndNullBothReadAsEmptyList() {
        assertTrue(converter.convertToEntityAttribute("[]").isEmpty());
        assertTrue(converter.convertToEntityAttribute(null).isEmpty());
    }

    @Test
    void roundTrip_values() {
        String json = converter.convertToDatabaseColumn(List.of("a", "b"));
        assertEquals(List.of("a", "b"), converter.convertToEntityAttribute(json));
    }
}
