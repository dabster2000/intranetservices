package dk.trustworks.intranet.recruitmentservice.slack;

import com.slack.api.model.block.ActionsBlock;
import com.slack.api.model.block.LayoutBlock;
import com.slack.api.model.block.SectionBlock;
import com.slack.api.model.block.element.ButtonElement;
import dk.trustworks.intranet.recruitmentservice.model.RecruitmentAvailabilityConstraint;
import dk.trustworks.intranet.recruitmentservice.model.RecruitmentAvailabilityEvidence;
import dk.trustworks.intranet.recruitmentservice.model.enums.AvailabilityConstraintType;
import org.junit.jupiter.api.Test;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The D6 confirmable surfaces (plan §12.4), pinned DB-free: the summary
 * card renders ONLY from parsed structure, its buttons carry the
 * evidence-uuid claim, the language flips with the evidence row, and
 * every template stays inside Slack's 3 000-char limit.
 */
class SlackAvailabilityViewsTest {

    private static final LocalDateTime TUE_9 = LocalDateTime.of(2026, 8, 18, 9, 0);

    // ---- The summary card -------------------------------------------------

    @Test
    void pendingSummary_hasDanishReading_andBothButtonsWithTheEvidenceClaim() {
        RecruitmentAvailabilityEvidence evidence = evidence("da");
        List<LayoutBlock> blocks = SlackAvailabilityViews.summaryCard(evidence,
                List.of(constraint(AvailabilityConstraintType.BUSY, TUE_9, TUE_9.plusHours(3))),
                true);

        String text = ((SectionBlock) blocks.get(0)).getText().getText();
        assertTrue(text.contains("Jeg har læst din besked sådan:"), text);
        assertTrue(text.contains("tirsdag den 18. august kl. 09.00–12.00: optaget"), text);
        assertTrue(text.contains("Er det korrekt?"), text);

        ActionsBlock actions = (ActionsBlock) blocks.get(1);
        List<String> actionIds = actions.getElements().stream()
                .map(element -> ((ButtonElement) element).getActionId())
                .toList();
        assertEquals(List.of(
                SlackAvailabilityViews.ACTION_EVIDENCE_CONFIRM,
                SlackAvailabilityViews.ACTION_EVIDENCE_CORRECT), actionIds);
        actions.getElements().forEach(element ->
                assertEquals("evidence-1", ((ButtonElement) element).getValue()));
    }

    @Test
    void registeredSummary_offersOnlyTheRetEscapeHatch_andSaysNoConfirmNeeded() {
        // Auto-confirmed (requiresConfirmation=false): no Bekræft to
        // press — the registered card still carries Ret (D9's undo) and
        // SAYS no confirmation is needed (the Ret-only card was read as
        // a missing button in the 2026-08-15 retest).
        List<LayoutBlock> blocks = SlackAvailabilityViews.summaryCard(evidence("da"),
                List.of(constraint(AvailabilityConstraintType.BUSY, TUE_9, TUE_9.plusHours(3))),
                false);
        String text = ((SectionBlock) blocks.get(0)).getText().getText();
        assertTrue(text.contains("Registreret"), text);

        String note = ((com.slack.api.model.block.composition.MarkdownTextObject)
                ((com.slack.api.model.block.ContextBlock) blocks.get(1))
                        .getElements().get(0)).getText();
        assertTrue(note.contains("ingen bekræftelse nødvendig"), note);

        ActionsBlock actions = (ActionsBlock) blocks.get(2);
        assertEquals(1, actions.getElements().size());
        assertEquals(SlackAvailabilityViews.ACTION_EVIDENCE_CORRECT,
                ((ButtonElement) actions.getElements().getFirst()).getActionId());
    }

    @Test
    void englishEvidence_rendersTheEnglishSurfaces() {
        RecruitmentAvailabilityEvidence evidence = evidence("en");
        List<LayoutBlock> blocks = SlackAvailabilityViews.summaryCard(evidence,
                List.of(constraint(AvailabilityConstraintType.AVAILABLE_ONLY,
                        TUE_9.plusHours(4), TUE_9.plusHours(7))),
                true);
        String text = ((SectionBlock) blocks.get(0)).getText().getText();
        assertTrue(text.contains("I read your message like this:"), text);
        assertTrue(text.contains("Tuesday 18 August 13:00–16:00: ONLY available"), text);

        ActionsBlock actions = (ActionsBlock) blocks.get(1);
        assertEquals("Confirm", ((ButtonElement) actions.getElements().get(0))
                .getText().getText());
        assertEquals("Correct", ((ButtonElement) actions.getElements().get(1))
                .getText().getText());
    }

    @Test
    void constraintLines_coverAllFourTypes_inBothLanguages() {
        RecruitmentAvailabilityConstraint preferred =
                constraint(AvailabilityConstraintType.PREFERRED, TUE_9, TUE_9.plusHours(1));
        RecruitmentAvailabilityConstraint avoid =
                constraint(AvailabilityConstraintType.AVOID, TUE_9, TUE_9.plusHours(1));
        assertTrue(SlackAvailabilityViews.constraintLine(preferred, false).endsWith("helst"));
        assertTrue(SlackAvailabilityViews.constraintLine(avoid, false).endsWith("helst ikke"));
        assertTrue(SlackAvailabilityViews.constraintLine(preferred, true).endsWith("preferably"));
        assertTrue(SlackAvailabilityViews.constraintLine(avoid, true).endsWith("preferably not"));
    }

    @Test
    void multiDayIntervals_spellOutBothEnds() {
        String line = SlackAvailabilityViews.interval(
                TUE_9, TUE_9.plusDays(2).plusHours(3), false);
        assertTrue(line.startsWith("fra tirsdag den 18. august kl. 09.00"), line);
        assertTrue(line.contains("til torsdag den 20. august kl. 12.00"), line);
    }

    // ---- Canned replies ---------------------------------------------------

    @Test
    void cannedReplies_existInBothLanguages_andTheNoMatchOnesAreDanish() {
        // F14 (owner-reported, 2026-08-14): "rekruttereren" is malformed
        // Danish — the copy says "rekrutteringsteamet" everywhere.
        assertTrue(SlackAvailabilityViews.NO_ACTIVE_TEXT.contains("rekrutteringsteamet"));
        assertTrue(SlackAvailabilityViews.AMBIGUOUS_TEXT.contains("tråden"));
        assertTrue(SlackAvailabilityViews.useButtonsText("da").contains("knapperne"));
        assertTrue(SlackAvailabilityViews.useButtonsText("en").contains("buttons"));
        assertTrue(SlackAvailabilityViews.unparseableText("da").contains("præcise datoer"));
        assertTrue(SlackAvailabilityViews.unparseableText("en").contains("exact dates"));
        assertTrue(SlackAvailabilityViews.routedAckText("da").contains("rekrutteringsteamet"));
        assertTrue(SlackAvailabilityViews.routedAckText("en").contains("recruitment team"));
        // Phase 13: the image-path replies. Pre-extraction ones are
        // Danish (language unknown before the model ran).
        assertTrue(SlackAvailabilityViews.imageFetchFailedText().contains("hente billedet"));
        assertTrue(SlackAvailabilityViews.unsupportedImageText().contains("20 MB"));
        assertTrue(SlackAvailabilityViews.imageUnreadableText("da").contains("kalender"));
        assertTrue(SlackAvailabilityViews.imageUnreadableText("en").contains("calendar"));
    }

    @Test
    void reconfirmNotice_namesTheBookedSlot() {
        String danish = SlackAvailabilityViews.reconfirmNoticeText("da",
                TUE_9.plusHours(1), TUE_9.plusHours(2));
        assertTrue(danish.contains("tirsdag den 18. august kl. 10.00–11.00"), danish);
        assertTrue(danish.contains("udløbet"), danish);
        String english = SlackAvailabilityViews.reconfirmNoticeText("en",
                TUE_9.plusHours(1), TUE_9.plusHours(2));
        assertTrue(english.contains("Tuesday 18 August 10:00–11:00"), english);
    }

    // ---- Limits -----------------------------------------------------------

    @Test
    void summaryText_staysInsideSlacksLimit_evenAtTheConstraintCap() {
        RecruitmentAvailabilityEvidence evidence = evidence("da");
        List<RecruitmentAvailabilityConstraint> many = new java.util.ArrayList<>();
        for (int i = 0; i < 20; i++) {
            many.add(constraint(AvailabilityConstraintType.BUSY,
                    TUE_9.plusDays(i % 5), TUE_9.plusDays(i % 5).plusHours(3)));
        }
        String clamped = SlackAvailabilityViews.clamp(
                SlackAvailabilityViews.summaryText(evidence, many, true));
        assertTrue(clamped.length() <= 3000);
    }

    @Test
    void aiPrefix_isTheMandatedRobotMarker() {
        assertEquals("🤖 ", SlackAvailabilityViews.AI_PREFIX);
    }

    // ---- Helpers ----------------------------------------------------------

    private static RecruitmentAvailabilityEvidence evidence(String language) {
        RecruitmentAvailabilityEvidence evidence = new RecruitmentAvailabilityEvidence();
        evidence.setUuid("evidence-1");
        evidence.setRequestUuid("request-1");
        evidence.setUserUuid("user-1");
        evidence.setLanguage(language);
        evidence.setTimezone("Europe/Copenhagen");
        evidence.setCoveredFrom(LocalDate.of(2026, 8, 18));
        evidence.setCoveredTo(LocalDate.of(2026, 8, 21));
        return evidence;
    }

    private static RecruitmentAvailabilityConstraint constraint(
            AvailabilityConstraintType type, LocalDateTime start, LocalDateTime end) {
        RecruitmentAvailabilityConstraint constraint = new RecruitmentAvailabilityConstraint();
        constraint.setUuid("constraint-1");
        constraint.setEvidenceUuid("evidence-1");
        constraint.setType(type);
        constraint.setStartAt(start);
        constraint.setEndAt(end);
        return constraint;
    }
}
