package dk.trustworks.intranet.recruitmentservice.slack;

import com.slack.api.model.block.LayoutBlock;
import dk.trustworks.intranet.recruitmentservice.model.RecruitmentAvailabilityConstraint;
import dk.trustworks.intranet.recruitmentservice.model.RecruitmentAvailabilityEvidence;

import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

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

    /** The module's Slack text clamp (Block Kit 3 000-char limit). */
    static final int TEXT_MAX = 3000;

    /** The mandatory visual marker on AI prose (D6). */
    public static final String AI_PREFIX = "🤖 ";

    /** Plan §12.2's no-match reply — Danish by decree. */
    public static final String NO_ACTIVE_TEXT =
            "Der er ingen aktiv interviewplanlægning at knytte din besked til — "
                    + "brug knapperne på et interviewforslag, eller kontakt rekruttereren.";

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
        StringBuilder sb = new StringBuilder(256);
        if (pendingConfirmation) {
            sb.append(en ? "*I read your message like this:*"
                    : "*Jeg har læst din besked sådan:*");
        } else {
            sb.append(en ? "*Registered — I will schedule around this:*"
                    : "*Registreret — jeg planlægger uden om dette:*");
        }
        for (RecruitmentAvailabilityConstraint constraint : constraints) {
            sb.append("\n• ").append(constraintLine(constraint, en));
        }
        if (evidence.getCoveredFrom() != null && evidence.getCoveredTo() != null) {
            sb.append("\n_").append(en ? "Applies to " : "Gælder ")
                    .append(evidence.getCoveredFrom().format(en
                            ? DateTimeFormatter.ofPattern("d MMMM", Locale.ENGLISH)
                            : DateTimeFormatter.ofPattern("d. MMMM", DANISH)))
                    .append("–")
                    .append(evidence.getCoveredTo().format(en
                            ? DateTimeFormatter.ofPattern("d MMMM", Locale.ENGLISH)
                            : DateTimeFormatter.ofPattern("d. MMMM", DANISH)))
                    .append(" (").append(evidence.getTimezone()).append(")_");
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
                ? "I have passed your message on to the recruiter, who will get "
                        + "back to you."
                : "Jeg har sendt din besked videre til rekruttereren, som vender "
                        + "tilbage til dig.";
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
                        + "recruiter right away."
                : "Interviewet er booket "
                        + interval(slotStart, slotEnd, false)
                        + ". Din eksterne kalenderbesked for perioden var udløbet — "
                        + "hvis tiden ikke længere passer, så kontakt rekruttereren "
                        + "med det samme.";
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
