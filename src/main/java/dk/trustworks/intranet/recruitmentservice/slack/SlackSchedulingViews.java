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

    /** One Danish point in time: {@code fredag den 22. august kl. 16.00}. */
    public static String danishDayTime(LocalDateTime at) {
        return at.format(DAY) + " kl. " + at.format(TIME);
    }

    /** The card's one-line interview label: "Interview 2" / "Uformel snak". */
    public static String interviewLabel(RecruitmentInterviewKind kind, Integer round) {
        return kind == RecruitmentInterviewKind.INFORMAL
                ? "Uformel snak"
                : "Interview " + (round != null ? round : 1);
    }

    /**
     * The interviewer-guidance line every proposal card carries (F13
     * free-text-primary + F15, owner decisions 2026-08-14): the card's
     * two buttons answer THIS proposal; everything else — availability,
     * calendar screenshots, questions — is a plain message in the same
     * chat, and the card is where the interviewer learns that.
     */
    static final String CARD_GUIDANCE =
            "Passer tiden ikke? Skriv dine ledige tider her i chatten — eller send "
                    + "et screenshot af din kalender — så foreslår jeg nye tider ud "
                    + "fra dem. Spørgsmål kan også bare skrives her; de sendes videre "
                    + "til rekrutteringsteamet.";

    /**
     * One option's state as ONE interviewer's combined card renders it —
     * derived purely from (slot status, own approval status, reject
     * reason), so the whole card is a render of persisted state and
     * every rewrite is just "render again".
     */
    public enum OptionState {
        /** Awaiting THIS interviewer's answer — buttons live. */
        OPEN,
        /** This interviewer approved; the slot is still in play. */
        APPROVED_BY_ME,
        /** This interviewer pressed Afvis. */
        DECLINED_BY_ME,
        /** This interviewer answered with a message instead (F12). */
        ANSWERED_BY_MESSAGE,
        /** The candidate chose this option — it became the interview. */
        BOOKED,
        /** Died elsewhere: a colleague declined, a conflict, a release. */
        CLOSED
    }

    /** The (slot, own approval, reject reason) → state mapping (pure;
     * DB-free tested). */
    public static OptionState optionState(
            dk.trustworks.intranet.recruitmentservice.model.enums.ProposedSlotStatus slot,
            dk.trustworks.intranet.recruitmentservice.model.enums.SlotApprovalStatus mine,
            String rejectReason) {
        if (slot == dk.trustworks.intranet.recruitmentservice.model.enums
                .ProposedSlotStatus.FINALIZED) {
            return OptionState.BOOKED;
        }
        if (mine == dk.trustworks.intranet.recruitmentservice.model.enums
                .SlotApprovalStatus.DECLINED) {
            return dk.trustworks.intranet.recruitmentservice.services
                    .RecruitmentSchedulingService.REASON_INTERVIEWER_REPLIED
                            .equals(rejectReason)
                    ? OptionState.ANSWERED_BY_MESSAGE : OptionState.DECLINED_BY_ME;
        }
        if (slot.isTerminal()) {
            return OptionState.CLOSED;
        }
        if (mine == dk.trustworks.intranet.recruitmentservice.model.enums
                .SlotApprovalStatus.APPROVED) {
            return OptionState.APPROVED_BY_ME;
        }
        return OptionState.OPEN;
    }

    /** One option row of the combined card. */
    public record OptionView(RecruitmentProposedSlot slot, String approvalUuid,
                             OptionState state) {
    }

    /**
     * THE proposal message (one per interviewer per proposal round): a
     * header naming the interview, one block per option with its state —
     * OPEN options carry their own Godkend/Afvis pair (values = the
     * approval uuid, a CLAIM the handlers re-authorize) — and the
     * free-text guidance while anything is still open (F13/F15). Every
     * state change re-renders this same message in place, so the card
     * always shows the whole round's truth instead of three drifting
     * single-slot cards.
     */
    public static List<LayoutBlock> combinedProposalCard(
            RecruitmentSchedulingRequest request,
            List<OptionView> options,
            String candidateName,
            String positionTitle,
            int nudge) {
        List<LayoutBlock> blocks = new ArrayList<>();
        if (nudge > 0) {
            blocks.add(section(s -> s.text(markdownText(
                    ":bell: *Påmindelse* — interviewforslag venter stadig på dit svar."))));
        }
        blocks.add(section(s -> s.text(markdownText(headerLine(request,
                candidateName, positionTitle, options.size())))));
        for (OptionView option : options) {
            blocks.add(section(s -> s.text(markdownText(optionLine(option)))));
            if (option.state() == OptionState.OPEN) {
                blocks.add(actions(a -> a.elements(asElements(
                        button(b -> b.actionId(ACTION_APPROVE)
                                .value(option.approvalUuid())
                                .style("primary").text(plainText("Godkend"))),
                        button(b -> b.actionId(ACTION_DECLINE)
                                .value(option.approvalUuid())
                                .style("danger").text(plainText("Afvis")))))));
            }
        }
        boolean anyOpen = options.stream()
                .anyMatch(option -> option.state() == OptionState.OPEN);
        if (anyOpen) {
            blocks.add(context(c -> c.elements(asContextElements(markdownText(
                    "Svar pr. mulighed — \"Godkend\" reserverer tiden foreløbigt "
                            + "i din kalender. " + CARD_GUIDANCE)))));
        } else {
            blocks.add(context(c -> c.elements(asContextElements(markdownText(
                    ":white_check_mark: Alle forslag er besvaret — du behøver ikke "
                            + "foretage dig mere her.")))));
        }
        return blocks;
    }

    /** The combined card's header: interview, candidate, shape. */
    static String headerLine(RecruitmentSchedulingRequest request,
                             String candidateName, String positionTitle,
                             int optionCount) {
        StringBuilder text = new StringBuilder(":calendar: *")
                .append(interviewLabel(request.getKind(), request.getRound()))
                .append(": ")
                .append(SlackCandidateFacts.mrkdwnSafe(candidateName))
                .append("*");
        if (positionTitle != null && !positionTitle.isBlank()) {
            text.append(" — ").append(SlackCandidateFacts.mrkdwnSafe(positionTitle));
        }
        text.append("\n").append(request.getDurationMinutes()).append(" min");
        if (request.isOnlineMeeting()) {
            text.append(" · Microsoft Teams");
        } else if (request.getLocation() != null && !request.getLocation().isBlank()) {
            text.append(" · ").append(SlackCandidateFacts.mrkdwnSafe(request.getLocation()));
        }
        text.append(" · ").append(optionCount == 1
                ? "1 forslag" : optionCount + " forslag");
        return text.toString();
    }

    /** One option's mrkdwn row: number, time, room, state. */
    static String optionLine(OptionView option) {
        RecruitmentProposedSlot slot = option.slot();
        StringBuilder text = new StringBuilder("*Mulighed ")
                .append(slot.getOptionNo()).append("* · *")
                .append(danishInterval(slot.getSlotStart(), slot.getSlotEnd()))
                .append("*");
        if (slot.getRoomName() != null && !slot.getRoomName().isBlank()) {
            text.append(" · :round_pushpin: ")
                    .append(SlackCandidateFacts.mrkdwnSafe(slot.getRoomName()));
        }
        String stateLine = switch (option.state()) {
            case OPEN -> null;
            case APPROVED_BY_ME ->
                    ":white_check_mark: Godkendt — tiden er reserveret foreløbigt.";
            case DECLINED_BY_ME -> ":x: Afvist.";
            case ANSWERED_BY_MESSAGE -> ":speech_balloon: Besvaret med besked.";
            case BOOKED -> ":tada: Kandidaten valgte denne tid — interviewet er "
                    + "booket, og invitationen kommer i Outlook.";
            case CLOSED -> ":no_entry_sign: Lukket — kræver ikke svar.";
        };
        if (stateLine != null) {
            text.append("\n").append(stateLine);
        }
        return text.toString();
    }

    /** The combined message's notification/preview text. */
    public static String combinedFallback(int optionCount, int nudge, boolean anyBooked) {
        if (anyBooked) {
            return "Interview booket";
        }
        String base = optionCount == 1
                ? "Interviewforslag" : "Interviewforslag — " + optionCount + " muligheder";
        return nudge > 0 ? "Påmindelse: " + base : base;
    }

    /** The one-line booked notice DM'd to each required interviewer. */
    public static String bookedNotice(RecruitmentProposedSlot slot, String candidateName) {
        return ":white_check_mark: Interviewet med "
                + SlackCandidateFacts.mrkdwnSafe(candidateName) + " er booket "
                + danishInterval(slot.getSlotStart(), slot.getSlotEnd())
                + ". Du får invitationen i Outlook; øvrige foreløbige reserveringer "
                + "er frigivet.";
    }


    // ---- Note modals -------------------------------------------------------
    // The buttons that open these are gone from NEW cards (F13 — free
    // text is the primary path), but cards already delivered before the
    // change still carry them, so the modals stay serviceable.

    /** "Foreslå anden tid" (legacy cards): the note now feeds the same
     * availability pipeline as a plain chat message (F7). */
    public static View suggestModal(String privateMetadata) {
        return noteModal(SUGGEST_SUBMIT, privateMetadata, "Foreslå anden tid",
                "Send",
                "Skriv hvilke tider der passer dig — fx \"onsdage efter kl. 15\". "
                        + "Jeg planlægger ud fra dem og sender dem også videre til "
                        + "rekrutteringsteamet. Du kan altid bare skrive direkte i "
                        + "chatten i stedet.");
    }

    /** "Spørg rekrutteringsteamet" (legacy cards): a plain question
     * routed to the recruiter. */
    public static View askModal(String privateMetadata) {
        return noteModal(ASK_SUBMIT, privateMetadata, "Spørg rekrutteringsteamet",
                "Send spørgsmål",
                "Dit spørgsmål sendes direkte til rekrutteringsteamet. Forslaget "
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
