package dk.trustworks.intranet.recruitmentservice.slack;

import com.slack.api.model.block.ActionsBlock;
import com.slack.api.model.block.ContextBlock;
import com.slack.api.model.block.InputBlock;
import com.slack.api.model.block.LayoutBlock;
import com.slack.api.model.block.SectionBlock;
import com.slack.api.model.block.composition.MarkdownTextObject;
import com.slack.api.model.block.element.PlainTextInputElement;
import com.slack.api.model.block.element.StaticSelectElement;
import com.slack.api.model.view.View;
import dk.trustworks.intranet.recruitmentservice.dto.PendingReferralAiSuggestions;
import dk.trustworks.intranet.recruitmentservice.model.RecruitmentReferral;
import dk.trustworks.intranet.recruitmentservice.model.ScorecardAttribute;
import org.junit.jupiter.api.Test;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Objects;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * P14 — the modal layouts as pure objects: callback ids, block ids,
 * required/optional marks, hint help-text presence on every non-obvious
 * input, prefills, AI-suggestion rendering, title limits and the mrkdwn
 * sentinel discipline (hostile names must never render as live Slack
 * markup — the P12 deviation-9 contract extended to modals).
 */
class SlackRecruitmentViewsTest {

    /** The P12 escaping sentinel: renders as a live link if unescaped. */
    private static final String HOSTILE_NAME = "<https://evil.example|Click here> & Sons";

    // ---- /refer modal -----------------------------------------------------------

    @Test
    void referModal_structureAndHelpText() {
        View view = SlackRecruitmentViews.referModal();
        assertEquals("modal", view.getType());
        assertEquals(SlackRecruitmentViews.REFER_SUBMIT, view.getCallbackId());
        assertTrue(view.getTitle().getText().length() <= 24, "Slack caps modal titles at 24 chars");

        List<InputBlock> inputs = inputs(view.getBlocks());
        assertEquals(List.of("candidate_name", "linkedin_url", "email", "relation",
                        "external_referrer_name", "why_text"),
                inputs.stream().map(InputBlock::getBlockId).toList());
        // Required/optional split mirrors the P6 form.
        assertFalse(isOptional(inputs.get(0)), "name is required");
        assertTrue(isOptional(inputs.get(1)), "linkedin is optional");
        assertFalse(isOptional(inputs.get(3)), "relation is required");
        assertFalse(isOptional(inputs.get(5)), "why-text is required");
        // Contextual help text on every input (the programme's UX standard).
        for (InputBlock input : inputs) {
            assertNotNull(input.getHint(), "input " + input.getBlockId() + " must carry a hint");
            assertFalse(input.getHint().getText().isBlank());
        }
        // The why field mirrors the service's 2000-char cap client-side.
        PlainTextInputElement why = (PlainTextInputElement) inputs.get(5).getElement();
        assertEquals(2000, why.getMaxLength());
        assertTrue(why.isMultiline());
    }

    @Test
    void referSubmittedView_carriesMyReferralsLink() {
        View view = SlackRecruitmentViews.referSubmittedView("https://intra.trustworks.dk");
        String text = allMrkdwn(view.getBlocks());
        assertTrue(text.contains("https://intra.trustworks.dk/recruitment/refer"));
        assertTrue(text.contains("milestone"), "confirmation explains the DM follow-ups");
        assertNull(view.getSubmit(), "confirmation views have no submit — only Done");
    }

    // ---- Triage modals ----------------------------------------------------------

    @Test
    void triageCreateModal_prefillsFromReferral_escapesHostileNames() {
        RecruitmentReferral referral = referral(HOSTILE_NAME);
        referral.setEmail("jane@example.com");
        referral.setLinkedinUrl("https://linkedin.com/in/jane");
        View view = SlackRecruitmentViews.triageCreateModal(
                referral, "Hans Referrer", null, List.of(), "{\"referralUuid\":\"r-1\"}");

        assertEquals(SlackRecruitmentViews.TRIAGE_CREATE_SUBMIT, view.getCallbackId());
        assertEquals("{\"referralUuid\":\"r-1\"}", view.getPrivateMetadata());
        assertTrue(view.getTitle().getText().length() <= 24);

        String intro = allMrkdwn(view.getBlocks());
        assertFalse(intro.contains("<https://evil.example|"),
                "hostile referral names must never render as live Slack links");
        assertTrue(intro.contains("&lt;https://evil.example|"), "escaped form expected");

        List<InputBlock> inputs = inputs(view.getBlocks());
        PlainTextInputElement first = (PlainTextInputElement) byBlockId(inputs, "first_name").getElement();
        PlainTextInputElement email = (PlainTextInputElement) byBlockId(inputs, "email").getElement();
        PlainTextInputElement linkedin = (PlainTextInputElement) byBlockId(inputs, "linkedin_url").getElement();
        // Name split prefill: first token / rest (plain text — no escaping).
        assertEquals("<https://evil.example|Click", first.getInitialValue());
        assertEquals("jane@example.com", email.getInitialValue());
        assertEquals("https://linkedin.com/in/jane", linkedin.getInitialValue());
        // No AI → no robot context block, and generic hints.
        assertFalse(intro.contains("AI suggestions"));
    }

    @Test
    void triageCreateModal_rendersAiPrefillsAndRationales() {
        PendingReferralAiSuggestions ai = new PendingReferralAiSuggestions(
                "p-1", "Data & AI", "SENIOR", "tl-1", "Thomas Teamlead",
                new PendingReferralAiSuggestions.Rationales(
                        "CV nævner ML-platform", "8 års erfaring", "Leder dataholdet"),
                LocalDateTime.now());
        View view = SlackRecruitmentViews.triageCreateModal(
                referral("Jane Jensen"), "Hans Referrer", ai,
                List.of(new SlackRecruitmentViews.TeamleadOption("tl-1", "Thomas Teamlead")),
                "{}");

        String text = allMrkdwn(view.getBlocks());
        assertTrue(text.contains("AI suggestions"), "AI context block present when suggestions exist");
        assertTrue(text.contains("Data &amp; AI") || text.contains("Data & AI"),
                "practice suggestion surfaces as context");

        List<InputBlock> inputs = inputs(view.getBlocks());
        StaticSelectElement experience =
                (StaticSelectElement) byBlockId(inputs, "experience_level").getElement();
        assertNotNull(experience.getInitialOption());
        assertEquals("SENIOR", experience.getInitialOption().getValue());
        StaticSelectElement teamlead =
                (StaticSelectElement) byBlockId(inputs, "relevant_teamlead").getElement();
        assertEquals("tl-1", teamlead.getInitialOption().getValue());
        // Rationales surface as the field hints.
        assertTrue(byBlockId(inputs, "experience_level").getHint().getText().contains("8 års erfaring"));
    }

    @Test
    void triageDismissModal_reasonRequired_consequencesStated() {
        View view = SlackRecruitmentViews.triageDismissModal(
                referral("Jane Jensen"), "Hans Referrer", "{}");
        assertEquals(SlackRecruitmentViews.TRIAGE_DISMISS_SUBMIT, view.getCallbackId());
        List<InputBlock> inputs = inputs(view.getBlocks());
        assertEquals(1, inputs.size());
        InputBlock reason = inputs.getFirst();
        assertEquals("dismiss_reason", reason.getBlockId());
        assertFalse(isOptional(reason));
        StaticSelectElement select = (StaticSelectElement) reason.getElement();
        assertArrayEquals(new String[]{"DUPLICATE", "NOT_RELEVANT", "OTHER"},
                select.getOptions().stream().map(o -> o.getValue()).toArray(String[]::new));
        String text = allMrkdwn(view.getBlocks());
        assertTrue(text.contains("not") && text.contains("shown the reason"),
                "the modal must state the referrer never sees the reason");
    }

    // ---- Capture modal ----------------------------------------------------------

    @Test
    void captureModal_prefillsMessage_privateExplained() {
        View view = SlackRecruitmentViews.captureModal("met her at the conference", "{\"channelId\":\"C1\"}");
        assertEquals(SlackRecruitmentViews.CAPTURE_SUBMIT, view.getCallbackId());
        List<InputBlock> inputs = inputs(view.getBlocks());
        assertEquals(List.of("capture_candidate", "note_text", "note_private"),
                inputs.stream().map(InputBlock::getBlockId).toList());
        PlainTextInputElement note = (PlainTextInputElement) byBlockId(inputs, "note_text").getElement();
        assertEquals("met her at the conference", note.getInitialValue());
        assertEquals(3000, note.getMaxLength());
        // The candidate search is the external select the search handler owns.
        assertEquals(SlackRecruitmentViews.CAPTURE_CANDIDATE_SELECT,
                ((com.slack.api.model.block.element.ExternalSelectElement)
                        byBlockId(inputs, "capture_candidate").getElement()).getActionId());
        // Access + privacy semantics explained in plain language.
        assertTrue(byBlockId(inputs, "capture_candidate").getHint().getText()
                .contains("candidates you have access to"));
    }

    @Test
    void captureModal_clampsOversizedMessagePrefill() {
        View view = SlackRecruitmentViews.captureModal("x".repeat(5000), "{}");
        PlainTextInputElement note = (PlainTextInputElement)
                byBlockId(inputs(view.getBlocks()), "note_text").getElement();
        assertEquals(3000, note.getInitialValue().length());
        assertTrue(note.getInitialValue().endsWith("…"));
    }

    // ---- Shared helpers ---------------------------------------------------------

    @Test
    void splitName_firstTokenRest() {
        assertArrayEquals(new String[]{"Jane", "Jensen"},
                SlackRecruitmentViews.splitName("Jane Jensen"));
        assertArrayEquals(new String[]{"Jane", "van der Berg"},
                SlackRecruitmentViews.splitName("  Jane van der Berg "));
        assertArrayEquals(new String[]{"Cher", ""}, SlackRecruitmentViews.splitName("Cher"));
        assertArrayEquals(new String[]{"", ""}, SlackRecruitmentViews.splitName(null));
    }

    @Test
    void outcomeAndConfirmationViews_escapeHostileContent() {
        View created = SlackRecruitmentViews.candidateCreatedView(
                HOSTILE_NAME, "cand-1", "https://intra.trustworks.dk");
        String text = allMrkdwn(created.getBlocks());
        assertFalse(text.contains("<https://evil.example|"));
        assertTrue(text.contains("/recruitment/candidates/cand-1"));

        View saved = SlackRecruitmentViews.noteSavedView(
                HOSTILE_NAME, "cand-1", "https://intra.trustworks.dk", true);
        String savedText = allMrkdwn(saved.getBlocks());
        assertFalse(savedText.contains("<https://evil.example|"));
        assertTrue(savedText.contains("(private)"), "privacy choice is confirmed back");
    }

    // ---- Scorecard modal (P18) ---------------------------------------------------

    @Test
    void scorecardModal_templateDriven_blindRuleExplained() {
        List<ScorecardAttribute> template = List.of(
                new ScorecardAttribute("WHY_CONSULTING", "Why consulting"),
                new ScorecardAttribute("CULTURE_FIT", "Culture fit"));
        View view = SlackRecruitmentViews.scorecardModal(
                HOSTILE_NAME, "Senior Consultant", 2, template, "interview-1");

        assertEquals("modal", view.getType());
        assertEquals(SlackRecruitmentViews.SCORECARD_SUBMIT, view.getCallbackId());
        assertEquals("interview-1", view.getPrivateMetadata(),
                "private_metadata carries the interview uuid (plan §P18)");
        assertTrue(view.getTitle().getText().length() <= 24, "Slack caps modal titles at 24 chars");

        List<InputBlock> inputs = inputs(view.getBlocks());
        assertEquals(List.of("score_WHY_CONSULTING", "score_CULTURE_FIT",
                        "recommendation", "scorecard_notes"),
                inputs.stream().map(InputBlock::getBlockId).toList(),
                "one select per template attribute, in template order");
        // Scores and recommendation required; notes optional (the web form's split).
        assertFalse(isOptional(inputs.get(0)));
        assertFalse(isOptional(inputs.get(2)));
        assertTrue(isOptional(inputs.get(3)));
        // The 1–4 selects carry the web dialog's anchors.
        StaticSelectElement score = (StaticSelectElement) inputs.get(0).getElement();
        assertEquals(List.of("1", "2", "3", "4"),
                score.getOptions().stream().map(o -> o.getValue()).toList());
        assertTrue(score.getOptions().get(0).getText().getText().contains("Clear concern"));
        assertTrue(score.getOptions().get(3).getText().getText().contains("Outstanding"));
        // Notes mirror the service's 2000-char cap client-side, with help text.
        PlainTextInputElement notes = (PlainTextInputElement) inputs.get(3).getElement();
        assertEquals(2000, notes.getMaxLength());
        assertTrue(notes.isMultiline());
        assertNotNull(inputs.get(3).getHint());
        assertNotNull(inputs.get(2).getHint(), "recommendation carries its help text");

        String text = allMrkdwn(view.getBlocks());
        assertTrue(text.contains("blind"), "the blind rule is explained in plain language");
        assertTrue(text.contains("90 seconds"), "the 90-second promise (spec §8.5)");
        assertFalse(text.contains("<https://evil.example|"),
                "hostile candidate names never render as live markup");
    }

    @Test
    void scorecardSubmittedView_progressCopy_neverContent() {
        View waiting = SlackRecruitmentViews.scorecardSubmittedView(
                HOSTILE_NAME, 2, "https://intra.trustworks.dk");
        String waitingText = allMrkdwn(waiting.getBlocks());
        assertTrue(waitingText.contains("Waiting for 2 more scorecards"));
        assertTrue(waitingText.contains("/recruitment/interviews"), "debrief deep link");
        assertFalse(waitingText.contains("<https://evil.example|"));
        assertNull(waiting.getSubmit(), "confirmation views have no submit — only Done");

        View last = SlackRecruitmentViews.scorecardSubmittedView(
                "Jane Jensen", 0, "https://intra.trustworks.dk");
        String lastText = allMrkdwn(last.getBlocks());
        assertTrue(lastText.contains("the debrief is ready"),
                "last-card copy announces debrief readiness");
        assertTrue(lastText.contains("decision owner"),
                "…and that the owner is notified — no decision buttons in Slack");
    }

    @Test
    void scorecardActions_singlePrimaryButton_carriesInterviewUuid() {
        LayoutBlock block = SlackRecruitmentViews.scorecardActions("interview-42");
        assertTrue(block instanceof ActionsBlock);
        ActionsBlock actions = (ActionsBlock) block;
        assertEquals(1, actions.getElements().size(), "one button — the deep link lives in the text");
        com.slack.api.model.block.element.ButtonElement button =
                (com.slack.api.model.block.element.ButtonElement) actions.getElements().get(0);
        assertEquals(SlackRecruitmentViews.SCORECARD_OPEN, button.getActionId());
        assertEquals("interview-42", button.getValue());
        assertEquals("primary", button.getStyle());
        assertEquals("Fill in scorecard", button.getText().getText());
    }

    private static RecruitmentReferral referral(String candidateName) {
        RecruitmentReferral referral = new RecruitmentReferral();
        referral.setCandidateName(candidateName);
        referral.setWhyText("why");
        return referral;
    }

    private static List<InputBlock> inputs(List<LayoutBlock> blocks) {
        return blocks.stream()
                .filter(InputBlock.class::isInstance)
                .map(InputBlock.class::cast)
                .toList();
    }

    private static InputBlock byBlockId(List<InputBlock> inputs, String blockId) {
        return inputs.stream().filter(i -> blockId.equals(i.getBlockId())).findFirst().orElseThrow();
    }

    private static boolean isOptional(InputBlock input) {
        return input.isOptional();
    }

    /** Every mrkdwn text in the view, joined — sections and context blocks. */
    private static String allMrkdwn(List<LayoutBlock> blocks) {
        return blocks.stream().map(block -> {
            if (block instanceof SectionBlock section && section.getText() != null) {
                return section.getText().getText();
            }
            if (block instanceof ContextBlock context) {
                return context.getElements().stream()
                        .filter(MarkdownTextObject.class::isInstance)
                        .map(e -> ((MarkdownTextObject) e).getText())
                        .collect(Collectors.joining("\n"));
            }
            if (block instanceof ActionsBlock) {
                return "";
            }
            return "";
        }).filter(Objects::nonNull).collect(Collectors.joining("\n"));
    }
}
