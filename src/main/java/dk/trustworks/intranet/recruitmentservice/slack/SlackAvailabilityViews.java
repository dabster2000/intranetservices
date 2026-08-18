package dk.trustworks.intranet.recruitmentservice.slack;

import com.slack.api.model.block.LayoutBlock;
import dk.trustworks.intranet.recruitmentservice.model.RecruitmentAvailabilityConstraint;
import dk.trustworks.intranet.recruitmentservice.model.RecruitmentAvailabilityEvidence;
import dk.trustworks.intranet.recruitmentservice.model.enums.AvailabilityConstraintType;
import dk.trustworks.intranet.recruitmentservice.model.enums.EvidenceSourceType;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

import static com.slack.api.model.block.Blocks.actions;
import static com.slack.api.model.block.Blocks.context;
import static com.slack.api.model.block.Blocks.section;
import static com.slack.api.model.block.composition.BlockCompositions.markdownText;
import static com.slack.api.model.block.composition.BlockCompositions.plainText;
import static com.slack.api.model.block.element.BlockElements.asContextElements;
import static com.slack.api.model.block.element.BlockElements.asElements;
import static com.slack.api.model.block.element.BlockElements.button;

/**
 * Deterministic templates of the Method B availability loop (plan
 * §12.4, D6): the summary the interviewer confirms, the Bekræft/Ret
 * rewrites, and every canned reply. Composed ONLY from parsed
 * constraints — model prose never renders here; ambiguities surface as
 * a fixed caution line, and the one AI-authored artifact (the
 * clarifying question) is sent separately with the mandatory "🤖 "
 * prefix by {@code AvailabilityMessageService}.
 * <p>
 * Danish templates are the default; the extraction's detected language
 * flips the confirmable surfaces to English (§13.1). Pre-extraction
 * replies (no-active, ambiguous correlation) are Danish by plan §12.2.
 * <p>
 * Pure and CDI-free — template wording, button values and the 3 000-char
 * clamp are pinned by DB-free tests.
 */
public final class SlackAvailabilityViews {

    // Handler keys (inbound allowlist) — the confirmation loop's buttons.
    public static final String ACTION_EVIDENCE_CONFIRM = "recruitment_sched_evidence_confirm";
    public static final String ACTION_EVIDENCE_CORRECT = "recruitment_sched_evidence_correct";

    /** The Ret modal's view_submission callback id. */
    public static final String CORRECT_SUBMIT = "recruitment_sched_evidence_correct_submit";

    /** The Ret modal's single input block. */
    public static final String BLOCK_CORRECTION = "correction";

    /** The module's Slack text clamp (Block Kit 3 000-char limit). */
    static final int TEXT_MAX = 3000;

    /** The mandatory visual marker on AI prose (D6). */
    public static final String AI_PREFIX = "🤖 ";

    /** Plan §12.2's no-match reply — Danish by decree. */
    public static final String NO_ACTIVE_TEXT =
            "Der er ingen aktiv interviewplanlægning at knytte din besked til — "
                    + "brug knapperne på et interviewforslag, eller kontakt "
                    + "rekrutteringsteamet.";

    /** Several active requests and no thread anchor — still deterministic Danish. */
    public static final String AMBIGUOUS_TEXT =
            "Du har flere aktive interviewplanlægninger — svar i tråden under det "
                    + "relevante interviewforslag, så jeg ved hvilken din besked gælder.";

    private static final Locale DANISH = Locale.of("da", "DK");
    private static final DateTimeFormatter DAY_DA =
            DateTimeFormatter.ofPattern("EEEE 'den' d. MMMM", DANISH);
    private static final DateTimeFormatter DAY_EN =
            DateTimeFormatter.ofPattern("EEEE d MMMM", Locale.ENGLISH);
    private static final DateTimeFormatter TIME = DateTimeFormatter.ofPattern("HH.mm");
    private static final DateTimeFormatter TIME_EN = DateTimeFormatter.ofPattern("HH:mm");

    private SlackAvailabilityViews() {
    }

    // ------------------------------------------------------------------
    // The summary card (the D6/D9 confirmable)
    // ------------------------------------------------------------------

    /**
     * The summary the interviewer must confirm — or, for an
     * auto-confirmed unambiguous statement, is shown as registered with
     * only a Ret escape hatch. Every button carries the evidence uuid
     * as its value (a CLAIM the handlers re-authorize).
     */
    public static List<LayoutBlock> summaryCard(RecruitmentAvailabilityEvidence evidence,
                                                List<RecruitmentAvailabilityConstraint> constraints,
                                                boolean pendingConfirmation) {
        boolean en = isEnglish(evidence);
        List<LayoutBlock> blocks = new ArrayList<>();
        blocks.add(section(s -> s.text(markdownText(clamp(summaryText(
                evidence, constraints, pendingConfirmation))))));
        if (pendingConfirmation) {
            blocks.add(actions(a -> a.elements(asElements(
                    button(b -> b.actionId(ACTION_EVIDENCE_CONFIRM)
                            .value(evidence.getUuid())
                            .style("primary")
                            .text(plainText(en ? "Confirm" : "Bekræft"))),
                    button(b -> b.actionId(ACTION_EVIDENCE_CORRECT)
                            .value(evidence.getUuid())
                            .text(plainText(en ? "Correct" : "Ret")))))));
        } else {
            // The auto-confirmed card was read as a MISSING Bekræft
            // button in the 2026-08-15 retest — say out loud that no
            // confirmation is needed.
            blocks.add(context(c -> c.elements(asContextElements(markdownText(en
                    ? "Registered automatically — no confirmation needed. "
                            + "Press Correct if anything is wrong."
                    : "Registreret automatisk — ingen bekræftelse nødvendig. "
                            + "Tryk Ret, hvis noget er forkert.")))));
            blocks.add(actions(a -> a.elements(asElements(
                    button(b -> b.actionId(ACTION_EVIDENCE_CORRECT)
                            .value(evidence.getUuid())
                            .text(plainText(en ? "Correct" : "Ret")))))));
        }
        return blocks;
    }

    /** The card's mrkdwn body — package-visible so tests pin the wording. */
    static String summaryText(RecruitmentAvailabilityEvidence evidence,
                              List<RecruitmentAvailabilityConstraint> constraints,
                              boolean pendingConfirmation) {
        boolean en = isEnglish(evidence);
        StringBuilder sb = new StringBuilder(512);
        if (pendingConfirmation) {
            sb.append(en ? "*I read your message like this:*"
                    : "*Jeg har læst din besked sådan:*");
        } else {
            sb.append(en ? "*Registered — this now guides the scheduling:*"
                    : "*Registreret — dette indgår nu i planlægningen:*");
        }

        // v2: render EVERY covered day, including the free ones. Listing only
        // busy intervals made a fabricated busy day and an omitted busy day
        // look identical to the reviewer — the 2026-08-18 production misread
        // reported two empty weekdays as fully booked and nothing on the card
        // contradicted it.
        List<RecruitmentAvailabilityConstraint> busy = new ArrayList<>();
        List<RecruitmentAvailabilityConstraint> other = new ArrayList<>();
        for (RecruitmentAvailabilityConstraint constraint : constraints) {
            if (constraint.getType() == AvailabilityConstraintType.BUSY) {
                busy.add(constraint);
            } else {
                other.add(constraint);
            }
        }
        Set<LocalDate> unreadable = parseUnreadableDays(evidence);
        LocalDate from = evidence.getCoveredFrom();
        LocalDate to = evidence.getCoveredTo();

        if (from != null && to != null && !from.isAfter(to)
                && from.plusDays(MAX_DAY_LINES).isAfter(to)) {
            for (LocalDate day = from; !day.isAfter(to); day = day.plusDays(1)) {
                sb.append("\n• ").append(dayLine(day, busy, unreadable, en));
            }
        } else {
            // No covered range (or an implausibly long one): fall back to the
            // flat interval list rather than inventing day lines.
            for (RecruitmentAvailabilityConstraint constraint : busy) {
                sb.append("\n• ").append(constraintLine(constraint, en));
            }
        }
        for (RecruitmentAvailabilityConstraint constraint : other) {
            sb.append("\n• ").append(constraintLine(constraint, en));
        }

        if (from != null && to != null) {
            sb.append("\n_").append(en ? "Applies to " : "Gælder ")
                    .append(from.format(en
                            ? DateTimeFormatter.ofPattern("d MMMM", Locale.ENGLISH)
                            : DateTimeFormatter.ofPattern("d. MMMM", DANISH)))
                    .append("–")
                    .append(to.format(en
                            ? DateTimeFormatter.ofPattern("d MMMM", Locale.ENGLISH)
                            : DateTimeFormatter.ofPattern("d. MMMM", DANISH)))
                    .append(" (").append(evidence.getTimezone()).append(")_");
        }
        String disclosure = sourceDisclosure(evidence, en);
        if (disclosure != null) {
            sb.append("\n_").append(disclosure).append("_");
        }
        if (pendingConfirmation) {
            sb.append("\n").append(en
                    ? "Is this correct? If anything is missing or wrong, press Correct "
                            + "and write it again."
                    : "Er det korrekt? Hvis noget mangler eller er forkert, så tryk Ret "
                            + "og skriv det igen.");
        }
        return sb.toString();
    }

    /**
     * The image cost backstop's reply. Deterministic Danish, and it hands the
     * interviewer a route that still works rather than a dead end.
     */
    public static String imageCapReachedText() {
        return "Jeg har modtaget rigtig mange kalenderbilleder til den her "
                + "planlægning. Skriv i stedet dine tider som tekst — fx "
                + "\"optaget tirsdag 9-12, fri resten af ugen\" — så læser jeg dem "
                + "med det samme. Rekrutteringsteamet kan også hjælpe direkte.";
    }

    /** Beyond this many covered days the per-day list stops being readable. */
    static final int MAX_DAY_LINES = 21;

    /** Slack's plain_text_input initial-value ceiling we stay well inside. */
    static final int PREFILL_MAX = 2800;

    /**
     * The Ret modal, PREFILLED with the current reading so a correction is an
     * edit rather than a retype.
     * <p>
     * This is the asymmetry that decided real-world data quality: confirming a
     * wrong reading cost one tap, while correcting it cancelled the evidence and
     * demanded the interviewer retype a whole week of a complex calendar from
     * scratch. A busy person confirms instead. Prefilling inverts that at zero
     * added friction — the same single button, but now it opens something you
     * can fix a line in.
     */
    public static com.slack.api.model.view.View correctModal(String evidenceUuid,
                                                             String prefill, boolean en) {
        String hint = en
                ? "Edit the lines that are wrong and send. Times are Europe/Copenhagen. "
                        + "Anything you leave stands as written."
                : "Ret de linjer, der er forkerte, og send. Tider er i Europe/Copenhagen. "
                        + "Det, du lader stå, gælder som skrevet.";
        return com.slack.api.model.view.Views.view(v -> v
                .type("modal")
                .callbackId(CORRECT_SUBMIT)
                .privateMetadata(evidenceUuid)
                .title(com.slack.api.model.view.Views.viewTitle(t -> t.type("plain_text")
                        .text(en ? "Correct the reading" : "Ret læsningen")))
                .submit(com.slack.api.model.view.Views.viewSubmit(x -> x.type("plain_text")
                        .text(en ? "Save" : "Gem")))
                .close(com.slack.api.model.view.Views.viewClose(c -> c.type("plain_text")
                        .text(en ? "Cancel" : "Annuller")))
                .blocks(com.slack.api.model.block.Blocks.asBlocks(
                        com.slack.api.model.block.Blocks.input(i -> i
                                .blockId(BLOCK_CORRECTION)
                                .label(plainText(en ? "Your availability" : "Din tilgængelighed"))
                                .hint(plainText(hint))
                                .element(com.slack.api.model.block.element.BlockElements
                                        .plainTextInput(t -> t
                                                .actionId(BLOCK_CORRECTION)
                                                .multiline(true)
                                                .initialValue(clampPrefill(prefill))))))));
    }

    /**
     * The editable plain-text rendering of a reading: one line per covered day,
     * absolute dates and explicit times — deliberately the shape the TEXT
     * extraction reads most reliably, since the edited result goes straight back
     * through it.
     */
    public static String correctionPrefill(RecruitmentAvailabilityEvidence evidence,
                                           List<RecruitmentAvailabilityConstraint> constraints) {
        boolean en = isEnglish(evidence);
        List<RecruitmentAvailabilityConstraint> busy = new ArrayList<>();
        for (RecruitmentAvailabilityConstraint constraint : constraints) {
            if (constraint.getType() == AvailabilityConstraintType.BUSY) {
                busy.add(constraint);
            }
        }
        Set<LocalDate> unreadable = parseUnreadableDays(evidence);
        LocalDate from = evidence.getCoveredFrom();
        LocalDate to = evidence.getCoveredTo();
        StringBuilder sb = new StringBuilder(400);
        if (from != null && to != null && !from.isAfter(to)
                && from.plusDays(MAX_DAY_LINES).isAfter(to)) {
            for (LocalDate day = from; !day.isAfter(to); day = day.plusDays(1)) {
                if (sb.length() > 0) {
                    sb.append("\n");
                }
                if (unreadable.contains(day)) {
                    // Give the interviewer a line to fill in rather than a
                    // status message to delete.
                    sb.append(day.format(en ? DAY_EN : DAY_DA)).append(": ");
                } else {
                    sb.append(dayLine(day, busy, Set.of(), en));
                }
            }
        } else {
            for (RecruitmentAvailabilityConstraint constraint : constraints) {
                if (sb.length() > 0) {
                    sb.append("\n");
                }
                sb.append(constraintLine(constraint, en));
            }
        }
        return sb.toString();
    }

    static String clampPrefill(String prefill) {
        if (prefill == null) {
            return "";
        }
        return prefill.length() <= PREFILL_MAX ? prefill : prefill.substring(0, PREFILL_MAX);
    }

    /**
     * One day's line: its busy intervals, or an explicit "free" — or, when the
     * vision pass could not read that column, an explicit "could not read" so a
     * discarded day is never mistaken for a free one.
     */
    static String dayLine(LocalDate day, List<RecruitmentAvailabilityConstraint> busy,
                          Set<LocalDate> unreadable, boolean en) {
        String label = day.format(en ? DAY_EN : DAY_DA);
        if (unreadable.contains(day)) {
            return label + ": " + (en
                    ? "could not be read — not registered"
                    : "kunne ikke læses — ikke registreret");
        }
        List<RecruitmentAvailabilityConstraint> onDay = busy.stream()
                .filter(c -> coversDay(c, day))
                .sorted(Comparator.comparing(RecruitmentAvailabilityConstraint::getStartAt))
                .toList();
        if (onDay.isEmpty()) {
            return label + ": " + (en ? "free" : "fri");
        }
        StringBuilder line = new StringBuilder(label).append(": ");
        boolean first = true;
        for (RecruitmentAvailabilityConstraint constraint : onDay) {
            if (!first) {
                line.append(", ");
            }
            if (isWholeDay(constraint, day)) {
                line.append(en ? "busy all day" : "optaget hele dagen");
            } else {
                if (first) {
                    line.append(en ? "busy " : "optaget kl. ");
                }
                line.append(clipToDay(constraint, day, en));
            }
            first = false;
        }
        return line.toString();
    }

    private static boolean coversDay(RecruitmentAvailabilityConstraint constraint, LocalDate day) {
        return !constraint.getStartAt().toLocalDate().isAfter(day)
                && !constraint.getEndAt().toLocalDate().isBefore(day);
    }

    private static boolean isWholeDay(RecruitmentAvailabilityConstraint constraint, LocalDate day) {
        boolean startsAtOrBefore = !constraint.getStartAt().isAfter(day.atStartOfDay());
        boolean endsAtOrAfter = !constraint.getEndAt().isBefore(day.atTime(23, 59));
        return startsAtOrBefore && endsAtOrAfter;
    }

    /** The visible part of one interval on one day, as HH.mm–HH.mm. */
    private static String clipToDay(RecruitmentAvailabilityConstraint constraint,
                                    LocalDate day, boolean en) {
        LocalDateTime start = constraint.getStartAt().toLocalDate().equals(day)
                ? constraint.getStartAt() : day.atStartOfDay();
        LocalDateTime end = constraint.getEndAt().toLocalDate().equals(day)
                ? constraint.getEndAt() : day.atTime(23, 59);
        DateTimeFormatter fmt = en ? TIME_EN : TIME;
        return start.format(fmt) + "–" + end.format(fmt);
    }

    /** The persisted unreadable-day list, parsed defensively. */
    static Set<LocalDate> parseUnreadableDays(RecruitmentAvailabilityEvidence evidence) {
        String raw = evidence.getUnreadableDays();
        if (raw == null || raw.isBlank()) {
            return Set.of();
        }
        Set<LocalDate> days = new LinkedHashSet<>();
        for (String token : raw.split(",")) {
            try {
                days.add(LocalDate.parse(token.trim()));
            } catch (Exception ignored) {
                // A malformed stored token must never break the card.
            }
        }
        return days;
    }

    /**
     * Says where the reading came from, and — for an image — whether a second
     * pass corroborated it. Disclosure only: the owner's standing decision is
     * that this path stays frictionless, so nothing here asks for extra work.
     */
    static String sourceDisclosure(RecruitmentAvailabilityEvidence evidence, boolean en) {
        if (evidence.getSourceType() != EvidenceSourceType.IMAGE) {
            return null;
        }
        String trust = evidence.getReadTrust();
        boolean uncorroborated = trust != null && trust.contains("NOT_CORROBORATED");
        if (uncorroborated) {
            return en
                    ? "Read from your calendar image. This reading could not be double-checked — "
                            + "please glance over the days above."
                    : "Læst fra dit kalenderbillede. Læsningen kunne ikke dobbeltcheckes — "
                            + "skimt gerne dagene ovenfor.";
        }
        return en
                ? "Read from your calendar image and double-checked."
                : "Læst fra dit kalenderbillede og dobbeltchecket.";
    }

    /** One deterministic constraint bullet, from structure only. */
    static String constraintLine(RecruitmentAvailabilityConstraint constraint, boolean en) {
        String label = switch (constraint.getType()) {
            case BUSY -> en ? "busy" : "optaget";
            case AVAILABLE_ONLY -> en ? "ONLY available" : "KUN tilgængelig";
            case PREFERRED -> en ? "preferably" : "helst";
            case AVOID -> en ? "preferably not" : "helst ikke";
        };
        return interval(constraint.getStartAt(), constraint.getEndAt(), en) + ": " + label;
    }

    /** Same-day → the §29.15 long format; multi-day → an explicit from/to. */
    static String interval(LocalDateTime start, LocalDateTime end, boolean en) {
        if (start.toLocalDate().equals(end.toLocalDate())) {
            return en
                    ? start.format(DAY_EN) + " " + start.format(TIME_EN) + "–" + end.format(TIME_EN)
                    : start.format(DAY_DA) + " kl. " + start.format(TIME) + "–" + end.format(TIME);
        }
        return en
                ? "from " + start.format(DAY_EN) + " " + start.format(TIME_EN)
                        + " to " + end.format(DAY_EN) + " " + end.format(TIME_EN)
                : "fra " + start.format(DAY_DA) + " kl. " + start.format(TIME)
                        + " til " + end.format(DAY_DA) + " kl. " + end.format(TIME);
    }

    // ------------------------------------------------------------------
    // The Bekræft/Ret rewrites (chat.update in place)
    // ------------------------------------------------------------------

    /** After Bekræft — buttons gone, the confirmed reading stands. */
    public static List<LayoutBlock> confirmedCard(RecruitmentAvailabilityEvidence evidence,
                                                  List<RecruitmentAvailabilityConstraint> constraints) {
        boolean en = isEnglish(evidence);
        return List.of(
                section(s -> s.text(markdownText(clamp(summaryText(evidence, constraints, false))))),
                context(c -> c.elements(asContextElements(markdownText(en
                        ? ":white_check_mark: Confirmed — this now guides the search."
                        : ":white_check_mark: Bekræftet — dette styrer nu søgningen.")))));
    }

    /** After Ret — buttons gone, the correction prompt stands. */
    public static List<LayoutBlock> cancelledCard(RecruitmentAvailabilityEvidence evidence) {
        boolean en = isEnglish(evidence);
        return List.of(
                context(c -> c.elements(asContextElements(markdownText(en
                        ? ":leftwards_arrow_with_hook: Discarded. Write your availability "
                                + "again as a new message here, and I will read it fresh."
                        : ":leftwards_arrow_with_hook: Kasseret. Skriv din tilgængelighed "
                                + "igen som en ny besked her, så læser jeg den forfra.")))));
    }

    // ------------------------------------------------------------------
    // Canned replies (deterministic, plan §12.2/§13.2)
    // ------------------------------------------------------------------

    /** APPROVE/DECLINE typed as text — the buttons stay authoritative. */
    public static String useButtonsText(String language) {
        return "en".equals(language)
                ? "Please answer with the buttons on the interview proposal — "
                        + "they update the request directly."
                : "Svar med knapperne på interviewforslaget — de opdaterer "
                        + "planlægningen direkte.";
    }

    /** Availability-shaped but unextractable — the deterministic
     * template pointing at buttons/precision (plan §12.3's UNKNOWN path). */
    public static String unparseableText(String language) {
        return "en".equals(language)
                ? "I could not turn your message into concrete times. Use the "
                        + "buttons on the proposal, or write exact dates and times "
                        + "— e.g. \"busy Tuesday 18 August 09:00–12:00\"."
                : "Jeg kunne ikke omsætte din besked til konkrete tidspunkter. "
                        + "Brug knapperne på forslaget, eller skriv præcise datoer "
                        + "og tidspunkter — fx \"optaget tirsdag den 18. august "
                        + "kl. 9–12\".";
    }

    /** The image could not be downloaded/stored — deterministic Danish
     * (pre-extraction, language unknown). */
    public static String imageFetchFailedText() {
        return "Jeg kunne ikke hente billedet fra Slack — prøv at sende det "
                + "igen, eller skriv din tilgængelighed som tekst.";
    }

    /** Wrong format or over the 20 MB cap — deterministic Danish. */
    public static String unsupportedImageText() {
        return "Billedformatet kan ikke bruges — send et JPEG-, PNG-, GIF- "
                + "eller WebP-billede under 20 MB, eller skriv din "
                + "tilgængelighed som tekst.";
    }

    /** The vision read yielded nothing usable (spec §11.5's unreadable rule). */
    public static String imageUnreadableText(String language) {
        return "en".equals(language)
                ? "I could not read a calendar from that image. Send a clearer "
                        + "screenshot, or write your availability as text."
                : "Jeg kunne ikke læse en kalender ud af billedet. Send et "
                        + "tydeligere skærmbillede, eller skriv din "
                        + "tilgængelighed som tekst.";
    }

    /** A routed intent's acknowledgement to the interviewer. */
    public static String routedAckText(String language) {
        return "en".equals(language)
                ? "I have passed your message on to the recruitment team, who will "
                        + "get back to you."
                : "Jeg har sendt din besked videre til rekrutteringsteamet, som "
                        + "vender tilbage til dig.";
    }

    /** The non-blocking stale-evidence notice at finalization (spec §23). */
    public static String reconfirmNoticeText(String language, LocalDateTime slotStart,
                                             LocalDateTime slotEnd) {
        boolean en = "en".equals(language);
        return en
                ? "The interview has been booked for "
                        + interval(slotStart, slotEnd, true)
                        + ". Your external-calendar information for that period had "
                        + "expired — if the time no longer works, contact the "
                        + "recruitment team right away."
                : "Interviewet er booket "
                        + interval(slotStart, slotEnd, false)
                        + ". Din eksterne kalenderbesked for perioden var udløbet — "
                        + "hvis tiden ikke længere passer, så kontakt "
                        + "rekrutteringsteamet med det samme.";
    }

    /** The summary DM's notification/preview text. */
    public static String summaryFallback(RecruitmentAvailabilityEvidence evidence) {
        return isEnglish(evidence)
                ? "Availability summary — please review"
                : "Tilgængelighed registreret — se efter";
    }

    private static boolean isEnglish(RecruitmentAvailabilityEvidence evidence) {
        return "en".equals(evidence.getLanguage());
    }

    public static String clamp(String text) {
        return text.length() <= TEXT_MAX ? text : text.substring(0, TEXT_MAX - 1) + "…";
    }
}
