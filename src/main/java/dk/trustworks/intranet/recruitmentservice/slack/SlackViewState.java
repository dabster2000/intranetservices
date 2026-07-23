package dk.trustworks.intranet.recruitmentservice.slack;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.util.List;
import java.util.Map;

/**
 * Read-only accessor over a {@code view_submission}'s
 * {@code view.state.values} JSON (P14). Slack's shape is
 * {@code {block_id: {action_id: {type, value | selected_option |
 * selected_options}}}}; the P14 modals use exactly one element per input
 * block, so lookups key on the block id alone and take the single action
 * entry beneath it.
 * <p>
 * Every accessor is null-safe and returns {@code null} (or {@code false})
 * for absent blocks — the modals mark required inputs as required, so
 * Slack enforces presence client-side; the services re-validate
 * server-side regardless (bean validation is inert, findings §P4).
 */
public final class SlackViewState {

    private static final TypeReference<Map<String, Map<String, Map<String, Object>>>> SHAPE =
            new TypeReference<>() {
            };

    private final Map<String, Map<String, Map<String, Object>>> values;

    private SlackViewState(Map<String, Map<String, Map<String, Object>>> values) {
        this.values = values;
    }

    /** Parses the raw {@code view.state.values} JSON; garbage ⇒ empty state. */
    public static SlackViewState parse(ObjectMapper mapper, String stateValuesJson) {
        if (stateValuesJson == null || stateValuesJson.isBlank()) {
            return new SlackViewState(Map.of());
        }
        try {
            return new SlackViewState(mapper.readValue(stateValuesJson, SHAPE));
        } catch (Exception e) {
            return new SlackViewState(Map.of());
        }
    }

    /** The trimmed value of a {@code plain_text_input} block, or null. */
    public String text(String blockId) {
        Object value = element(blockId).get("value");
        if (!(value instanceof String s)) {
            return null;
        }
        String trimmed = s.trim();
        return trimmed.isEmpty() ? null : trimmed;
    }

    /** The selected option value of a {@code static_select}/{@code external_select} block, or null. */
    public String selected(String blockId) {
        Object option = element(blockId).get("selected_option");
        if (option instanceof Map<?, ?> map && map.get("value") instanceof String s && !s.isBlank()) {
            return s;
        }
        return null;
    }

    /** True when a {@code checkboxes} block has the given option value ticked. */
    public boolean checked(String blockId, String optionValue) {
        Object options = element(blockId).get("selected_options");
        if (!(options instanceof List<?> list)) {
            return false;
        }
        return list.stream().anyMatch(o -> o instanceof Map<?, ?> map
                && optionValue.equals(map.get("value")));
    }

    private Map<String, Object> element(String blockId) {
        Map<String, Map<String, Object>> block = values.get(blockId);
        if (block == null || block.isEmpty()) {
            return Map.of();
        }
        return block.values().iterator().next();
    }
}
