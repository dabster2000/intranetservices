package dk.trustworks.intranet.recruitmentservice.slack;

import com.slack.api.model.block.LayoutBlock;
import com.slack.api.model.view.View;
import dk.trustworks.intranet.recruitmentservice.model.RecruitmentProposedSlot;
import dk.trustworks.intranet.recruitmentservice.model.RecruitmentSchedulingRequest;
import dk.trustworks.intranet.recruitmentservice.model.enums.RecruitmentInterviewKind;
import dk.trustworks.intranet.recruitmentservice.notifications.SlackCandidateFacts;

import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

import static com.slack.api.model.block.Blocks.actions;
import static com.slack.api.model.block.Blocks.asBlocks;
import static com.slack.api.model.block.Blocks.context;
import static com.slack.api.model.block.Blocks.input;
import static com.slack.api.model.block.Blocks.section;
import static com.slack.api.model.block.composition.BlockCompositions.markdownText;
import static com.slack.api.model.block.composition.BlockCompositions.plainText;
import static com.slack.api.model.block.element.BlockElements.asContextElements;
import static com.slack.api.model.block.element.BlockElements.asElements;
import static com.slack.api.model.block.element.BlockElements.button;
import static com.slack.api.model.block.element.BlockElements.plainTextInput;
import static com.slack.api.model.view.Views.view;
import static com.slack.api.model.view.Views.viewClose;
import static com.slack.api.model.view.Views.viewSubmit;
import static com.slack.api.model.view.Views.viewTitle;

/**
 * Block Kit shapes of the Method B interviewer loop (plan §9.1): the
 * proposal DM card with its four buttons, the answered/closed rewrites
 * (chat.update in place), and the two plain-note modals that route free
 * text to the recruiter until the Phase 12 NLU exists. Deterministic
 * templates only — no model prose ever reaches these (D6).
 * <p>
 * The candidate's name IS allowed here: a DM is a private surface and
 * the interviewer is an authorized participant (defaults §29.12 —
 * P22's concern is shared channels). Calendar holds stay name-free
 * (D12); this card is why the interviewer still knows who it is about.
 * <p>
 * Pure and CDI-free so payload shapes and the Danish long format are
 * pinned by DB-free tests (3 000-char limits, button values).
 */
public final class SlackSchedulingViews {

    // Handler keys (the inbound allowlist) — one per button/modal.
    public static final String ACTION_APPROVE = "recruitment_sched_approve";
    public static final String ACTION_DECLINE = "recruitment_sched_decline";
    public static final String ACTION_SUGGEST = "recruitment_sched_suggest";
    public static final String ACTION_ASK = "recruitment_sched_ask";
    public static final String SUGGEST_SUBMIT = "recruitment_sched_suggest_submit";
    public static final String ASK_SUBMIT = "recruitment_sched_ask_submit";

    /** The note input block of both modals. */
    public static final String BLOCK_NOTE = "sched_note";

    private static final Locale DANISH = Locale.of("da", "DK");
    private static final DateTimeFormatter DAY =
            DateTimeFormatter.ofPattern("EEEE 'den' d. MMMM", DANISH);
    private static final DateTimeFormatter TIME = DateTimeFormatter.ofPattern("HH.mm");

    private SlackSchedulingViews() {
    }

    /**
     * The spec's Danish long format: {@code tirsdag den 18. august
     * kl. 10.00–11.00}.
     */
    public static String danishInterval(LocalDateTime start, LocalDateTime end) {
        return start.format(DAY) + " kl. " + start.format(TIME) + "–" + end.format(TIME);
    }

    /** The card's one-line interview label: "Interview 2" / "Uformel snak". */
    public static String interviewLabel(RecruitmentInterviewKind kind, Integer round) {
        return kind == RecruitmentInterviewKind.INFORMAL
                ? "Uformel snak"
                : "Interview " + (round != null ? round : 1);
    }

    /**
     * The proposal DM card (plan §9.1). {@code nudge} &gt; 0 renders the
     * reminder variant (defaults §29.16). Every button carries the
     * approval uuid as its value — a CLAIM the handlers re-authorize.
     */
    public static List<LayoutBlock> proposalCard(RecruitmentSchedulingRequest request,
                                                 RecruitmentProposedSlot slot,
                                                 String candidateName,
                                                 String positionTitle,
                                                 String approvalUuid,
                                                 int nudge) {
        List<LayoutBlock> blocks = new ArrayList<>();
        if (nudge > 0) {
            blocks.add(section(s -> s.text(markdownText(
                    ":bell: *Påmindelse* — dette interviewforslag venter stadig på dit svar."))));
        }
        blocks.add(section(s -> s.text(markdownText(summaryLine(request, slot,
                candidateName, positionTitle)))));
        blocks.add(context(c -> c.elements(asContextElements(markdownText(
                "Mulighed " + slot.getOptionNo() + " · Svar med knapperne — "
                        + "\"Godkend\" reserverer tiden foreløbigt i din kalender.")))));
        blocks.add(actions(a -> a.elements(asElements(
                button(b -> b.actionId(ACTION_APPROVE).value(approvalUuid)
                        .style("primary").text(plainText("Godkend"))),
                button(b -> b.actionId(ACTION_DECLINE).value(approvalUuid)
                        .style("danger").text(plainText("Afvis"))),
                button(b -> b.actionId(ACTION_SUGGEST).value(approvalUuid)
                        .text(plainText("Foreslå anden tid"))),
                button(b -> b.actionId(ACTION_ASK).value(approvalUuid)
                        .text(plainText("Spørg rekruttereren")))))));
        return blocks;
    }

    /** The card after the interviewer answered — buttons gone, ✓/✗ shown. */
    public static List<LayoutBlock> answeredCard(RecruitmentSchedulingRequest request,
                                                 RecruitmentProposedSlot slot,
                                                 String candidateName,
                                                 String positionTitle,
                                                 boolean approved) {
        return asBlocks(
                section(s -> s.text(markdownText(summaryLine(request, slot,
                        candidateName, positionTitle)))),
                context(c -> c.elements(asContextElements(markdownText(approved
                        ? ":white_check_mark: Du har godkendt denne mulighed — tiden er reserveret foreløbigt."
                        : ":x: Du har afvist denne mulighed.")))));
    }

    /** The card when the slot died elsewhere (a colleague declined, a
     * conflict appeared, the request closed) — buttons gone. */
    public static List<LayoutBlock> closedCard(RecruitmentSchedulingRequest request,
                                               RecruitmentProposedSlot slot,
                                               String candidateName,
                                               String positionTitle) {
        return asBlocks(
                section(s -> s.text(markdownText(summaryLine(request, slot,
                        candidateName, positionTitle)))),
                context(c -> c.elements(asContextElements(markdownText(
                        ":no_entry_sign: Denne mulighed er lukket — du behøver ikke svare.")))));
    }

    /** The notification/preview text of the proposal DM. */
    public static String proposalFallback(RecruitmentProposedSlot slot, int nudge) {
        return (nudge > 0 ? "Påmindelse: interviewforslag " : "Interviewforslag ")
                + danishInterval(slot.getSlotStart(), slot.getSlotEnd());
    }

    private static String summaryLine(RecruitmentSchedulingRequest request,
                                      RecruitmentProposedSlot slot,
                                      String candidateName,
                                      String positionTitle) {
        StringBuilder text = new StringBuilder(":calendar: *")
                .append(interviewLabel(request.getKind(), request.getRound()))
                .append(": ")
                .append(SlackCandidateFacts.mrkdwnSafe(candidateName))
                .append("*");
        if (positionTitle != null && !positionTitle.isBlank()) {
            text.append(" — ").append(SlackCandidateFacts.mrkdwnSafe(positionTitle));
        }
        text.append("\n*")
                .append(danishInterval(slot.getSlotStart(), slot.getSlotEnd()))
                .append("* (").append(request.getDurationMinutes()).append(" min)");
        if (slot.getRoomName() != null && !slot.getRoomName().isBlank()) {
            text.append("\n:round_pushpin: ").append(SlackCandidateFacts.mrkdwnSafe(slot.getRoomName()));
        } else if (request.isOnlineMeeting()) {
            text.append("\n:round_pushpin: Microsoft Teams");
        } else if (request.getLocation() != null && !request.getLocation().isBlank()) {
            text.append("\n:round_pushpin: ").append(SlackCandidateFacts.mrkdwnSafe(request.getLocation()));
        }
        return text.toString();
    }

    // ---- Note modals (no NLU until Phase 12 — plain routed notes) ---------

    /** "Foreslå anden tid": a plain note routed to the recruiter. */
    public static View suggestModal(String privateMetadata) {
        return noteModal(SUGGEST_SUBMIT, privateMetadata, "Foreslå anden tid",
                "Send til rekruttereren",
                "Skriv hvilke tider der passer dig bedre — beskeden går direkte "
                        + "til rekruttereren, som finder et nyt forslag.");
    }

    /** "Spørg rekruttereren": a plain question routed to the recruiter. */
    public static View askModal(String privateMetadata) {
        return noteModal(ASK_SUBMIT, privateMetadata, "Spørg rekruttereren",
                "Send spørgsmål",
                "Dit spørgsmål sendes direkte til rekruttereren. Forslaget "
                        + "forbliver åbent, til du svarer med knapperne.");
    }

    private static View noteModal(String callbackId, String privateMetadata,
                                  String title, String submitLabel, String hint) {
        return view(v -> v
                .type("modal")
                .callbackId(callbackId)
                .privateMetadata(privateMetadata)
                .title(viewTitle(t -> t.type("plain_text").text(title)))
                .submit(viewSubmit(s -> s.type("plain_text").text(submitLabel)))
                .close(viewClose(c -> c.type("plain_text").text("Annuller")))
                .blocks(asBlocks(
                        input(i -> i
                                .blockId(BLOCK_NOTE)
                                .label(plainText("Besked"))
                                .element(plainTextInput(p -> p.actionId("value")
                                        .multiline(true)
                                        .maxLength(1000)))
                                .hint(plainText(hint))))));
    }
}
