package dk.trustworks.intranet.recruitmentservice.slack;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static com.slack.api.model.block.composition.BlockCompositions.option;
import static com.slack.api.model.block.composition.BlockCompositions.plainText;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * P14 — the synchronous Slack response bodies must be EXACTLY Slack's
 * wire shape (snake_case via the SDK's own Gson): a wrong key here and
 * modal errors silently stop rendering.
 */
class SlackResponseActionsTest {

    @Test
    void update_carriesSnakeCaseViewJson() {
        String json = SlackResponseActions.update(SlackRecruitmentViews.referSubmittedView(
                "https://intra.trustworks.dk"));
        JsonObject root = JsonParser.parseString(json).getAsJsonObject();
        assertEquals("update", root.get("response_action").getAsString());
        JsonObject view = root.getAsJsonObject("view");
        assertEquals("modal", view.get("type").getAsString());
        // snake_case proof on a nested SDK field: the close button text.
        assertTrue(view.toString().contains("\"text\":\"Done\""));
        assertTrue(view.toString().contains("My referrals"),
                "confirmation must carry the My-referrals deep link");
    }

    @Test
    void errors_anchorToBlockIds() {
        String json = SlackResponseActions.errors(Map.of(
                "linkedin_url", "linkedinUrl must be a linkedin.com profile link"));
        JsonObject root = JsonParser.parseString(json).getAsJsonObject();
        assertEquals("errors", root.get("response_action").getAsString());
        assertEquals("linkedinUrl must be a linkedin.com profile link",
                root.getAsJsonObject("errors").get("linkedin_url").getAsString());
    }

    @Test
    void options_serializeAsSlackOptionObjects() {
        String json = SlackResponseActions.options(List.of(
                option(plainText("Jane Jensen (jane@example.com)"), "cand-1")));
        JsonObject root = JsonParser.parseString(json).getAsJsonObject();
        var first = root.getAsJsonArray("options").get(0).getAsJsonObject();
        assertEquals("cand-1", first.get("value").getAsString());
        assertEquals("plain_text",
                first.getAsJsonObject("text").get("type").getAsString());
    }

    @Test
    void options_emptyListIsValid() {
        JsonObject root = JsonParser.parseString(
                SlackResponseActions.options(List.of())).getAsJsonObject();
        assertEquals(0, root.getAsJsonArray("options").size());
    }
}
