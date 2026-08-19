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
    void interviewLabel_coversEveryKind() {
        assertEquals("Interview 2",
                SlackSchedulingViews.interviewLabel(RecruitmentInterviewKind.ROUND, 2));
        assertEquals("Uformel snak",
                SlackSchedulingViews.interviewLabel(RecruitmentInterviewKind.INFORMAL, null));
        // The offer meeting carries no round: the old ternary would have
        // fallen through to the ROUND branch and printed "Interview 1".
        assertEquals("Samtale (tilbud)",
                SlackSchedulingViews.interviewLabel(RecruitmentInterviewKind.OFFER, null));
    }

    // ---- Combined proposal card -------------------------------------------

    private static SlackSchedulingViews.OptionView open(RecruitmentProposedSlot slot,
                                                        String approvalUuid) {
        return new SlackSchedulingViews.OptionView(slot, approvalUuid,
                SlackSchedulingViews.OptionState.OPEN);
    }

    @Test
    void combinedCard_carriesGodkendAfvisPerOpenOption_withTheApprovalClaims() {
        // One message per interviewer per round (owner request
        // 2026-08-15): every OPEN option carries its own Godkend/Afvis
        // pair, values = that option's approval uuid.
        List<LayoutBlock> blocks = SlackSchedulingViews.combinedProposalCard(
                request(), List.of(open(slot(), "approval-1"),
                        open(slot2(), "approval-2")),
                "Jane Doe", "Senior Consultant", 0);

        List<ActionsBlock> actions = blocks.stream()
                .filter(ActionsBlock.class::isInstance)
                .map(ActionsBlock.class::cast)
                .toList();
        assertEquals(2, actions.size(), "one button pair per open option");
        int i = 0;
        for (ActionsBlock block : actions) {
            i++;
            List<String> actionIds = block.getElements().stream()
                    .map(element -> ((ButtonElement) element).getActionId())
                    .toList();
            assertEquals(List.of(
                    SlackSchedulingViews.ACTION_APPROVE,
                    SlackSchedulingViews.ACTION_DECLINE), actionIds);
            String expected = "approval-" + i;
            block.getElements().forEach(element ->
                    assertEquals(expected, ((ButtonElement) element).getValue()));
        }

        // F15: the card itself explains free text and screenshots while
        // anything is still open.
        ContextBlock guidance = (ContextBlock) blocks.get(blocks.size() - 1);
        String text = ((com.slack.api.model.block.composition.MarkdownTextObject)
                guidance.getElements().get(0)).getText();
        assertTrue(text.contains("Skriv dine ledige tider"), text);
        assertTrue(text.contains("screenshot"), text);
        assertTrue(text.contains("rekrutteringsteamet"), text);
    }

    @Test
    void combinedCard_namesCandidateTimesAndRoom() {
        List<LayoutBlock> blocks = SlackSchedulingViews.combinedProposalCard(
                request(), List.of(open(slot(), "approval-1")),
                "Jane Doe", "Senior Consultant", 0);
        String header = ((SectionBlock) blocks.get(0)).getText().getText();
        assertTrue(header.contains("Jane Doe"), header);
        String option = ((SectionBlock) blocks.get(1)).getText().getText();
        assertTrue(option.contains("tirsdag den 18. august kl. 10.00–11.00"), option);
        assertTrue(option.contains("Lille mødelokale"), option);
        assertTrue(header.length() + option.length() < 3000,
                "mrkdwn text must stay inside Slack's limit");
    }

    @Test
    void combinedCard_nudgeVariant_prefixesTheReminder() {
        List<LayoutBlock> blocks = SlackSchedulingViews.combinedProposalCard(
                request(), List.of(open(slot(), "approval-1")), "Jane Doe", null, 1);
        String first = ((SectionBlock) blocks.get(0)).getText().getText();
        assertTrue(first.contains("Påmindelse"), first);
        assertEquals("Påmindelse: Interviewforslag — 3 muligheder",
                SlackSchedulingViews.combinedFallback(3, 1, false));
        assertEquals("Interview booket",
                SlackSchedulingViews.combinedFallback(3, 0, true));
    }

    @Test
    void combinedCard_escapesMrkdwnInNames() {
        List<LayoutBlock> blocks = SlackSchedulingViews.combinedProposalCard(
                request(), List.of(open(slot(), "approval-1")),
                "Jane <&> Doe", null, 0);
        String header = ((SectionBlock) blocks.get(0)).getText().getText();
        assertFalse(header.contains("<&>"), header);
    }

    @Test
    void combinedCard_settledOptionsCarryNoButtons_andSayAllAnswered() {
        List<LayoutBlock> blocks = SlackSchedulingViews.combinedProposalCard(
                request(), List.of(
                        new SlackSchedulingViews.OptionView(slot(), "approval-1",
                                SlackSchedulingViews.OptionState.APPROVED_BY_ME),
                        new SlackSchedulingViews.OptionView(slot2(), "approval-2",
                                SlackSchedulingViews.OptionState.CLOSED)),
                "Jane", null, 0);
        assertTrue(blocks.stream().noneMatch(ActionsBlock.class::isInstance),
                "settled cards must not keep live buttons");
        ContextBlock footer = (ContextBlock) blocks.get(blocks.size() - 1);
        String text = ((com.slack.api.model.block.composition.MarkdownTextObject)
                footer.getElements().get(0)).getText();
        assertTrue(text.contains("Alle forslag er besvaret"), text);
    }

    @Test
    void optionState_mapsSlotAndApprovalToTheRenderedState() {
        var proposed = dk.trustworks.intranet.recruitmentservice.model.enums
                .ProposedSlotStatus.PROPOSED;
        var rejected = dk.trustworks.intranet.recruitmentservice.model.enums
                .ProposedSlotStatus.REJECTED;
        var finalized = dk.trustworks.intranet.recruitmentservice.model.enums
                .ProposedSlotStatus.FINALIZED;
        var released = dk.trustworks.intranet.recruitmentservice.model.enums
                .ProposedSlotStatus.RELEASED;
        var held = dk.trustworks.intranet.recruitmentservice.model.enums
                .ProposedSlotStatus.HELD;
        var pending = dk.trustworks.intranet.recruitmentservice.model.enums
                .SlotApprovalStatus.PENDING;
        var approved = dk.trustworks.intranet.recruitmentservice.model.enums
                .SlotApprovalStatus.APPROVED;
        var declined = dk.trustworks.intranet.recruitmentservice.model.enums
                .SlotApprovalStatus.DECLINED;

        assertEquals(SlackSchedulingViews.OptionState.OPEN,
                SlackSchedulingViews.optionState(proposed, pending, null));
        assertEquals(SlackSchedulingViews.OptionState.APPROVED_BY_ME,
                SlackSchedulingViews.optionState(held, approved, null));
        assertEquals(SlackSchedulingViews.OptionState.DECLINED_BY_ME,
                SlackSchedulingViews.optionState(rejected, declined,
                        "INTERVIEWER_DECLINED"));
        assertEquals(SlackSchedulingViews.OptionState.ANSWERED_BY_MESSAGE,
                SlackSchedulingViews.optionState(rejected, declined,
                        "INTERVIEWER_REPLIED"));
        assertEquals(SlackSchedulingViews.OptionState.BOOKED,
                SlackSchedulingViews.optionState(finalized, approved, null));
        assertEquals(SlackSchedulingViews.OptionState.CLOSED,
                SlackSchedulingViews.optionState(rejected, pending,
                        "RECHECK_CONFLICT"));
        assertEquals(SlackSchedulingViews.OptionState.CLOSED,
                SlackSchedulingViews.optionState(released, approved, null));
    }

    @Test
    void bookedNotice_namesTheCandidateAndTheBookedTime() {
        // Spec §16.3: interviewers hear about the booking in Slack too.
        String notice = SlackSchedulingViews.bookedNotice(slot(), "Jane Doe");
        assertTrue(notice.contains("Jane Doe"), notice);
        assertTrue(notice.contains("tirsdag den 18. august kl. 10.00–11.00"), notice);
        assertTrue(notice.contains("Outlook"), notice);
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

    private static RecruitmentProposedSlot slot2() {
        RecruitmentProposedSlot slot = new RecruitmentProposedSlot();
        slot.setOptionNo(3);
        slot.setSlotStart(TUE_10.plusDays(1).plusHours(3));
        slot.setSlotEnd(TUE_10.plusDays(1).plusHours(4));
        return slot;
    }
}
