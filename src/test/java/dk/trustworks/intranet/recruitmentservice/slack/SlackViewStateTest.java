package dk.trustworks.intranet.recruitmentservice.slack;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * P14 — the {@code view.state.values} accessor: Slack's nested shape,
 * null-safety and the checkbox membership test.
 */
class SlackViewStateTest {

    private final ObjectMapper mapper = new ObjectMapper();

    private static final String STATE = """
            {
              "candidate_name": {"a1": {"type": "plain_text_input", "value": "  Jane Jensen  "}},
              "relation": {"a2": {"type": "static_select",
                                  "selected_option": {"value": "COLLEAGUE", "text": {"type": "plain_text", "text": "x"}}}},
              "note_private": {"a3": {"type": "checkboxes",
                                      "selected_options": [{"value": "private"}]}},
              "empty_text": {"a4": {"type": "plain_text_input", "value": "   "}},
              "no_selection": {"a5": {"type": "static_select", "selected_option": null}}
            }
            """;

    @Test
    void text_trimsAndNullsBlank() {
        SlackViewState state = SlackViewState.parse(mapper, STATE);
        assertEquals("Jane Jensen", state.text("candidate_name"));
        assertNull(state.text("empty_text"), "whitespace-only input reads as absent");
        assertNull(state.text("missing_block"));
    }

    @Test
    void selected_readsOptionValue_nullSafe() {
        SlackViewState state = SlackViewState.parse(mapper, STATE);
        assertEquals("COLLEAGUE", state.selected("relation"));
        assertNull(state.selected("no_selection"));
        assertNull(state.selected("missing_block"));
    }

    @Test
    void checked_matchesOptionValue() {
        SlackViewState state = SlackViewState.parse(mapper, STATE);
        assertTrue(state.checked("note_private", "private"));
        assertFalse(state.checked("note_private", "other"));
        assertFalse(state.checked("missing_block", "private"));
    }

    @Test
    void garbageJson_yieldsEmptyState() {
        SlackViewState state = SlackViewState.parse(mapper, "{not json");
        assertNull(state.text("candidate_name"));
        SlackViewState blank = SlackViewState.parse(mapper, "  ");
        assertNull(blank.selected("relation"));
    }
}
