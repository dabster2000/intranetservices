package dk.trustworks.intranet.recruitmentservice.notifications;

import com.slack.api.model.block.ActionsBlock;
import com.slack.api.model.block.LayoutBlock;
import com.slack.api.model.block.element.ButtonElement;
import dk.trustworks.intranet.recruitmentservice.slack.SlackCardViewButtonHandler;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The posting half of the {@code recruitment_card_view} regression: the
 * P22 living card must emit exactly the {@code action_id} that
 * {@link SlackCardViewButtonHandler} claims on the inbound allowlist. The
 * 2026-08-11 production warnings came from these two sides disagreeing —
 * the button existed, the allowlist entry did not.
 * <p>
 * DB-free: {@code CardState} is built straight from a
 * {@link SlackCandidateFacts} record, so no Panache lookup runs. The
 * end-to-end card lifecycle stays covered by {@code SlackCardReactorTest}
 * ({@code @QuarkusTest}).
 */
class SlackCardViewButtonTest {

    private static final String CANDIDATE_UUID = "8f2c1d44-0000-4000-8000-aaaaaaaaaaaa";
    private static final String BASE_URL = "https://intra.trustworks.dk";

    private static SlackCardReactor.CardState card(String candidateUuid, boolean anonymized) {
        SlackCandidateFacts facts = new SlackCandidateFacts(
                candidateUuid, "Jane Doe", "pos-uuid", "Senior Consultant",
                "SCREEN", "LINKEDIN_AD", "practice-uuid");
        return new SlackCardReactor.CardState(facts, anonymized,
                "SCREEN", null, null, 3L, 1L);
    }

    private static List<ButtonElement> buttonsOf(List<LayoutBlock> blocks) {
        return blocks.stream()
                .filter(ActionsBlock.class::isInstance)
                .map(ActionsBlock.class::cast)
                .flatMap(a -> a.getElements().stream())
                .map(ButtonElement.class::cast)
                .toList();
    }

    /**
     * The single assertion that would have caught the incident at build
     * time: the rendered action id IS the handler's allowlist key.
     */
    @Test
    void viewProfileButtonCarriesTheRegisteredAllowlistKey() {
        List<ButtonElement> buttons = buttonsOf(card(CANDIDATE_UUID, false).blocks(BASE_URL));

        assertEquals(1, buttons.size(), "the card carries exactly one button");
        ButtonElement view = buttons.getFirst();
        assertEquals(SlackCardViewButtonHandler.KEY, view.getActionId());
        assertEquals("recruitment_card_view", view.getActionId(),
                "the wire action_id Slack reports in block_actions payloads");
    }

    /** It is a URL button — that is why its handler is a deliberate no-op. */
    @Test
    void viewProfileButtonDeepLinksToTheCandidate() {
        ButtonElement view = buttonsOf(card(CANDIDATE_UUID, false).blocks(BASE_URL)).getFirst();

        assertEquals(BASE_URL + "/recruitment/candidates/" + CANDIDATE_UUID, view.getUrl());
        assertEquals("View profile", view.getText().getText());
    }

    /**
     * A redacted or unloadable candidate renders no button at all, so those
     * cards can never produce an inbound payload — the allowlist entry only
     * has to cover the linked case.
     */
    @Test
    void redactedAndUnloadableCardsRenderNoButton() {
        assertTrue(buttonsOf(card(CANDIDATE_UUID, true).blocks(BASE_URL)).isEmpty(),
                "an anonymized candidate keeps no deep link");
        assertTrue(buttonsOf(card(null, false).blocks(BASE_URL)).isEmpty(),
                "a card with no candidate uuid keeps no deep link");
        assertFalse(buttonsOf(card(CANDIDATE_UUID, false).blocks(BASE_URL)).isEmpty(),
                "the linked case still renders the button");
    }
}
