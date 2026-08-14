package dk.trustworks.intranet.recruitmentservice.slack;

import com.slack.api.model.block.ActionsBlock;
import com.slack.api.model.block.ContextBlock;
import com.slack.api.model.block.LayoutBlock;
import com.slack.api.model.block.SectionBlock;
import com.slack.api.model.block.element.ButtonElement;
import com.slack.api.model.view.View;
import dk.trustworks.intranet.recruitmentservice.model.RecruitmentProposedSlot;
import dk.trustworks.intranet.recruitmentservice.model.RecruitmentSchedulingRequest;
import dk.trustworks.intranet.recruitmentservice.model.enums.RecruitmentInterviewKind;
import org.junit.jupiter.api.Test;

import java.time.LocalDateTime;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The Method B Slack shapes (plan §9.1/§9.5): the spec's Danish long
 * format, the proposal card's buttons and claims, the answered/closed
 * rewrites and the note modals — pinned in the DB-free tier so a
 * payload regression (3 000-char limits, missing button values) fails
 * the deploy gate, not production.
 */
class SlackSchedulingViewsTest {

    private static final LocalDateTime TUE_10 = LocalDateTime.of(2026, 8, 18, 10, 0);

    // ---- Danish long format ----------------------------------------------

    @Test
    void danishInterval_matchesTheSpecExample() {
        // Defaults table §29.15: "tirsdag den 18. august kl. 10.00–11.00".
        assertEquals("tirsdag den 18. august kl. 10.00–11.00",
                SlackSchedulingViews.danishInterval(TUE_10, TUE_10.plusHours(1)));
    }

    @Test
    void interviewLabel_coversRoundsAndInformal() {
        assertEquals("Interview 2",
                SlackSchedulingViews.interviewLabel(RecruitmentInterviewKind.ROUND, 2));
        assertEquals("Uformel snak",
                SlackSchedulingViews.interviewLabel(RecruitmentInterviewKind.INFORMAL, null));
    }

    // ---- Proposal card ----------------------------------------------------

    @Test
    void proposalCard_carriesFourButtonsWithTheApprovalClaim() {
        List<LayoutBlock> blocks = SlackSchedulingViews.proposalCard(
                request(), slot(), "Jane Doe", "Senior Consultant", "approval-1", 0);

        ActionsBlock actions = (ActionsBlock) blocks.get(blocks.size() - 1);
        List<String> actionIds = actions.getElements().stream()
                .map(element -> ((ButtonElement) element).getActionId())
                .toList();
        assertEquals(List.of(
                SlackSchedulingViews.ACTION_APPROVE,
                SlackSchedulingViews.ACTION_DECLINE,
                SlackSchedulingViews.ACTION_SUGGEST,
                SlackSchedulingViews.ACTION_ASK), actionIds);
        actions.getElements().forEach(element ->
                assertEquals("approval-1", ((ButtonElement) element).getValue()));
    }

    @Test
    void proposalCard_namesCandidateTimeAndRoom() {
        List<LayoutBlock> blocks = SlackSchedulingViews.proposalCard(
                request(), slot(), "Jane Doe", "Senior Consultant", "approval-1", 0);
        String summary = ((SectionBlock) blocks.get(0)).getText().getText();
        assertTrue(summary.contains("Jane Doe"), summary);
        assertTrue(summary.contains("tirsdag den 18. august kl. 10.00–11.00"), summary);
        assertTrue(summary.contains("Lille mødelokale"), summary);
        assertTrue(summary.length() < 3000, "mrkdwn text must stay inside Slack's limit");
    }

    @Test
    void proposalCard_nudgeVariant_prefixesTheReminder() {
        List<LayoutBlock> blocks = SlackSchedulingViews.proposalCard(
                request(), slot(), "Jane Doe", null, "approval-1", 1);
        String first = ((SectionBlock) blocks.get(0)).getText().getText();
        assertTrue(first.contains("Påmindelse"), first);
        assertEquals("Påmindelse: interviewforslag tirsdag den 18. august kl. 10.00–11.00",
                SlackSchedulingViews.proposalFallback(slot(), 1));
    }

    @Test
    void proposalCard_escapesMrkdwnInNames() {
        List<LayoutBlock> blocks = SlackSchedulingViews.proposalCard(
                request(), slot(), "Jane <&> Doe", null, "approval-1", 0);
        String summary = ((SectionBlock) blocks.get(0)).getText().getText();
        assertFalse(summary.contains("<&>"), summary);
    }

    // ---- Answered / closed rewrites --------------------------------------

    @Test
    void answeredAndClosedCards_dropTheButtons() {
        for (List<LayoutBlock> blocks : List.of(
                SlackSchedulingViews.answeredCard(request(), slot(), "Jane", null, true),
                SlackSchedulingViews.answeredCard(request(), slot(), "Jane", null, false),
                SlackSchedulingViews.closedCard(request(), slot(), "Jane", null))) {
            assertTrue(blocks.stream().noneMatch(ActionsBlock.class::isInstance),
                    "rewritten cards must not keep live buttons");
            assertTrue(blocks.get(blocks.size() - 1) instanceof ContextBlock);
        }
    }

    // ---- Note modals ------------------------------------------------------

    @Test
    void noteModals_roundTripTheApprovalClaim() {
        View suggest = SlackSchedulingViews.suggestModal("approval-1");
        assertEquals(SlackSchedulingViews.SUGGEST_SUBMIT, suggest.getCallbackId());
        assertEquals("approval-1", suggest.getPrivateMetadata());
        assertNotNull(suggest.getSubmit());

        View ask = SlackSchedulingViews.askModal("approval-2");
        assertEquals(SlackSchedulingViews.ASK_SUBMIT, ask.getCallbackId());
        assertEquals("approval-2", ask.getPrivateMetadata());
    }

    // ---- Fixtures ---------------------------------------------------------

    private static RecruitmentSchedulingRequest request() {
        RecruitmentSchedulingRequest request = new RecruitmentSchedulingRequest();
        request.setKind(RecruitmentInterviewKind.ROUND);
        request.setRound(1);
        request.setDurationMinutes(60);
        request.setRequestedOptions(3);
        return request;
    }

    private static RecruitmentProposedSlot slot() {
        RecruitmentProposedSlot slot = new RecruitmentProposedSlot();
        slot.setOptionNo(2);
        slot.setSlotStart(TUE_10);
        slot.setSlotEnd(TUE_10.plusHours(1));
        slot.setRoomName("Lille mødelokale");
        return slot;
    }
}
