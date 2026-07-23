package dk.trustworks.intranet.recruitmentservice.slack;

import com.google.gson.Gson;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.slack.api.model.block.composition.OptionObject;
import com.slack.api.model.view.View;
import com.slack.api.util.json.GsonFactory;

import java.util.List;
import java.util.Map;

/**
 * Serializers for the synchronous JSON bodies Slack expects back from a
 * {@code view_submission} ({@code response_action}) or a
 * {@code block_suggestion} ({@code options}) — P14. The BFF relays these
 * verbatim as the HTTP response inside Slack's 3-second window
 * ({@code SlackInboundResponse.responseAction}).
 * <p>
 * Slack's own Gson configuration ({@link GsonFactory}) does the view
 * serialization — the SDK model classes carry Gson annotations, so this is
 * the one place Jackson would produce the wrong wire shape.
 */
public final class SlackResponseActions {

    private static final Gson GSON = GsonFactory.createSnakeCase();

    private SlackResponseActions() {
    }

    /** {@code response_action: update} — swap the open modal for {@code view}. */
    public static String update(View view) {
        JsonObject root = new JsonObject();
        root.addProperty("response_action", "update");
        root.add("view", GSON.toJsonTree(view));
        return GSON.toJson(root);
    }

    /**
     * {@code response_action: errors} — render inline validation errors on
     * the given input blocks ({@code block_id → message}).
     */
    public static String errors(Map<String, String> errorsByBlockId) {
        JsonObject root = new JsonObject();
        root.addProperty("response_action", "errors");
        root.add("errors", GSON.toJsonTree(errorsByBlockId));
        return GSON.toJson(root);
    }

    /** A {@code block_suggestion} options payload for an external select. */
    public static String options(List<OptionObject> options) {
        JsonObject root = new JsonObject();
        JsonArray array = new JsonArray();
        options.forEach(option -> array.add(GSON.toJsonTree(option)));
        root.add("options", array);
        return GSON.toJson(root);
    }
}
