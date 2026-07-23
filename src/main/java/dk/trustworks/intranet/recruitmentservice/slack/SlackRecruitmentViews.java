package dk.trustworks.intranet.recruitmentservice.slack;

import com.slack.api.model.block.LayoutBlock;
import com.slack.api.model.block.composition.OptionObject;
import com.slack.api.model.view.View;
import dk.trustworks.intranet.recruitmentservice.dto.PendingReferralAiSuggestions;
import dk.trustworks.intranet.recruitmentservice.model.RecruitmentReferral;
import dk.trustworks.intranet.recruitmentservice.model.enums.CandidateExperienceLevel;
import dk.trustworks.intranet.recruitmentservice.model.enums.RecruitmentReferralClosedReason;
import dk.trustworks.intranet.recruitmentservice.model.enums.RecruitmentReferralRelation;
import dk.trustworks.intranet.recruitmentservice.notifications.SlackCandidateFacts;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static com.slack.api.model.block.Blocks.asBlocks;
import static com.slack.api.model.block.Blocks.context;
import static com.slack.api.model.block.Blocks.divider;
import static com.slack.api.model.block.Blocks.input;
import static com.slack.api.model.block.Blocks.section;
import static com.slack.api.model.block.composition.BlockCompositions.markdownText;
import static com.slack.api.model.block.composition.BlockCompositions.option;
import static com.slack.api.model.block.composition.BlockCompositions.plainText;
import static com.slack.api.model.block.element.BlockElements.asContextElements;
import static com.slack.api.model.block.element.BlockElements.checkboxes;
import static com.slack.api.model.block.element.BlockElements.externalSelect;
import static com.slack.api.model.block.element.BlockElements.plainTextInput;
import static com.slack.api.model.block.element.BlockElements.staticSelect;
import static com.slack.api.model.view.Views.view;
import static com.slack.api.model.view.Views.viewClose;
import static com.slack.api.model.view.Views.viewSubmit;
import static com.slack.api.model.view.Views.viewTitle;

/**
 * The Block Kit views of the P14 Slack intake surface — {@code /refer},
 * referral triage and timeline capture. Pure builders (no I/O), so every
 * layout, label, hint and prefill is unit-testable without a Slack client.
 * <p>
 * UX rules applied throughout (plan §P14 + the programme's help-text
 * standard):
 * <ul>
 *   <li>Every non-obvious input carries a plain-language {@code hint},
 *       with a concrete example where one helps.</li>
 *   <li>Free text that lands in mrkdwn goes through
 *       {@link SlackCandidateFacts#mrkdwnSafe} — referral names and
 *       position titles are employee/applicant input (P12 deviation 9).
 *       Plain-text elements (labels, initial values, options) render
 *       literally and need no escaping.</li>
 *   <li>Modal titles stay under Slack's 24-character limit.</li>
 *   <li>Block ids are stable constants — the submission handlers key on
 *       them, and {@code response_action: errors} anchors to them.</li>
 * </ul>
 */
public final class SlackRecruitmentViews {

    // ---- Callback ids (view_submission handler keys) -------------------------
    public static final String REFER_SUBMIT = "recruitment_refer_submit";
    public static final String TRIAGE_CREATE_SUBMIT = "recruitment_triage_create_submit";
    public static final String TRIAGE_DISMISS_SUBMIT = "recruitment_triage_dismiss_submit";
    public static final String CAPTURE_SUBMIT = "recruitment_capture_submit";

    // ---- Block ids ------------------------------------------------------------
    public static final String BLOCK_CANDIDATE_NAME = "candidate_name";
    public static final String BLOCK_LINKEDIN = "linkedin_url";
    public static final String BLOCK_EMAIL = "email";
    public static final String BLOCK_RELATION = "relation";
    public static final String BLOCK_EXTERNAL_REFERRER = "external_referrer_name";
    public static final String BLOCK_WHY = "why_text";
    public static final String BLOCK_FIRST_NAME = "first_name";
    public static final String BLOCK_LAST_NAME = "last_name";
    public static final String BLOCK_PHONE = "phone";
    public static final String BLOCK_EXPERIENCE = "experience_level";
    public static final String BLOCK_TEAMLEAD = "relevant_teamlead";
    public static final String BLOCK_DISMISS_REASON = "dismiss_reason";
    public static final String BLOCK_CAPTURE_CANDIDATE = "capture_candidate";
    public static final String BLOCK_NOTE_TEXT = "note_text";
    public static final String BLOCK_NOTE_PRIVATE = "note_private";

    /** action_id of the capture modal's candidate search (block_suggestion key). */
    public static final String CAPTURE_CANDIDATE_SELECT = "recruitment_capture_candidate_select";

    /** The checkbox option value for "private note". */
    public static final String PRIVATE_OPTION = "private";

    /** Modal inputs cap notes at Slack's text-object limit. */
    static final int NOTE_MAX_LENGTH = 3000;

    private SlackRecruitmentViews() {
    }

    // ==========================================================================
    // /refer — the 60-second referral form (P6 twin)
    // ==========================================================================

    public static View referModal() {
        return view(v -> v
                .type("modal")
                .callbackId(REFER_SUBMIT)
                .title(viewTitle(t -> t.type("plain_text").text("Refer a candidate")))
                .submit(viewSubmit(s -> s.type("plain_text").text("Submit referral")))
                .close(viewClose(c -> c.type("plain_text").text("Cancel")))
                .blocks(asBlocks(
                        section(s -> s.text(markdownText(
                                "Know someone great for Trustworks? Fill this in — it takes about a "
                                        + "minute — and the recruitment team takes it from there. "
                                        + "You'll get a DM here whenever your referral reaches a "
                                        + "milestone (screening, interviews, offer, hire)."))),
                        input(i -> i
                                .blockId(BLOCK_CANDIDATE_NAME)
                                .label(plainText("Candidate name"))
                                .element(plainTextInput(t -> t.actionId("value").maxLength(200)
                                        .placeholder(plainText("e.g. Jane Jensen"))))
                                .hint(plainText("The person's full name."))),
                        input(i -> i
                                .blockId(BLOCK_LINKEDIN)
                                .optional(true)
                                .label(plainText("LinkedIn profile"))
                                .element(plainTextInput(t -> t.actionId("value").maxLength(500)
                                        .placeholder(plainText("linkedin.com/in/janejensen"))))
                                .hint(plainText("Paste their profile link if you have it — it saves "
                                        + "the recruiter the search. Only linkedin.com links work."))),
                        input(i -> i
                                .blockId(BLOCK_EMAIL)
                                .optional(true)
                                .label(plainText("Email"))
                                .element(plainTextInput(t -> t.actionId("value").maxLength(255)
                                        .placeholder(plainText("jane@example.com"))))
                                .hint(plainText("If you have it — lets the recruiter reach out directly."))),
                        input(i -> i
                                .blockId(BLOCK_RELATION)
                                .label(plainText("How do you know them?"))
                                .element(staticSelect(s -> s.actionId("value")
                                        .placeholder(plainText("Pick your relation"))
                                        .options(relationOptions())))
                                .hint(plainText("Helps the recruiter judge how warm the lead is."))),
                        input(i -> i
                                .blockId(BLOCK_EXTERNAL_REFERRER)
                                .optional(true)
                                .label(plainText("Recommended by someone else?"))
                                .element(plainTextInput(t -> t.actionId("value").maxLength(200)
                                        .placeholder(plainText("e.g. Peter Madsen, CTO at a client"))))
                                .hint(plainText("Only if you're passing on another person's tip — "
                                        + "write who actually knows the candidate."))),
                        input(i -> i
                                .blockId(BLOCK_WHY)
                                .label(plainText("Why are they a good fit?"))
                                .element(plainTextInput(t -> t.actionId("value").multiline(true)
                                        .maxLength(2000)
                                        .placeholder(plainText("e.g. Led the data platform team at her "
                                                + "current job — strong technically and great with clients."))))
                                .hint(plainText("A couple of sentences is plenty. What impressed you? "
                                        + "Only the recruitment team sees this text.")))
                )));
    }

    /** Post-submit confirmation (swapped in via {@code response_action: update}). */
    public static View referSubmittedView(String baseUrl) {
        return confirmationView("Referral submitted", asBlocks(
                section(s -> s.text(markdownText(
                        ":white_check_mark: *Thanks — your referral is in!*\n"
                                + "The recruitment team has been notified and will look at it shortly."))),
                section(s -> s.text(markdownText(
                        "Track its progress anytime under <" + baseUrl
                                + "/recruitment/refer|My referrals>."))),
                context(c -> c.elements(asContextElements(markdownText(
                        "You'll also get a DM here whenever it reaches a milestone — "
                                + "screening, interviews, offer or hire."))))));
    }

    // ==========================================================================
    // Triage — create candidate / dismiss (recruiter-only, opened from the ping)
    // ==========================================================================

    /**
     * The "Create candidate" modal, prefilled from the referral row and —
     * when the AI referral-triage toggle is on — the re-validated AI
     * suggestions (experience + teamlead prefills; practice as read-only
     * context, since the triage command has no practice field).
     */
    public static View triageCreateModal(RecruitmentReferral referral, String referrerName,
                                         PendingReferralAiSuggestions ai,
                                         List<TeamleadOption> teamleads,
                                         String privateMetadata) {
        String[] nameParts = splitName(referral.getCandidateName());
        List<LayoutBlock> blocks = new ArrayList<>();
        blocks.add(section(s -> s.text(markdownText(
                "*" + SlackCandidateFacts.mrkdwnSafe(referral.getCandidateName()) + "* — referred by "
                        + SlackCandidateFacts.mrkdwnSafe(referrerName)
                        + ".\nCheck the prefilled details and adjust before creating. The referral "
                        + "closes automatically once the candidate is created, and the referrer "
                        + "starts getting milestone updates."))));
        if (ai != null) {
            blocks.add(context(c -> c.elements(asContextElements(markdownText(aiContextLine(ai))))));
        }
        blocks.add(divider());
        blocks.add(input(i -> i
                .blockId(BLOCK_FIRST_NAME)
                .label(plainText("First name"))
                .element(plainTextInput(t -> t.actionId("value").maxLength(100)
                        .initialValue(nameParts[0])))
                .hint(plainText("Check the split — the referral form only had one name field."))));
        blocks.add(input(i -> i
                .blockId(BLOCK_LAST_NAME)
                .label(plainText("Last name"))
                .element(plainTextInput(t -> t.actionId("value").maxLength(100)
                        .initialValue(nameParts[1])))));
        blocks.add(input(i -> i
                .blockId(BLOCK_EMAIL)
                .optional(true)
                .label(plainText("Email"))
                .element(withInitial(plainTextInput(t -> t.actionId("value").maxLength(255)),
                        referral.getEmail()))
                .hint(plainText("From the referral — add or correct it if you know better."))));
        blocks.add(input(i -> i
                .blockId(BLOCK_PHONE)
                .optional(true)
                .label(plainText("Phone"))
                .element(plainTextInput(t -> t.actionId("value").maxLength(50)))));
        blocks.add(input(i -> i
                .blockId(BLOCK_LINKEDIN)
                .optional(true)
                .label(plainText("LinkedIn profile"))
                .element(withInitial(plainTextInput(t -> t.actionId("value").maxLength(500)),
                        referral.getLinkedinUrl()))
                .hint(plainText("Only linkedin.com links are accepted."))));
        blocks.add(input(i -> i
                .blockId(BLOCK_EXPERIENCE)
                .optional(true)
                .label(plainText("Experience level"))
                .element(staticSelect(s -> {
                    s.actionId("value").placeholder(plainText("Pick a level"))
                            .options(experienceOptions());
                    if (ai != null && ai.experienceLevel() != null) {
                        s.initialOption(experienceOption(ai.experienceLevel()));
                    }
                    return s;
                }))
                .hint(plainText(aiHint(ai == null ? null : ai.rationales().experienceLevel(),
                        "Your best estimate — it stays editable on the candidate.")))));
        blocks.add(input(i -> i
                .blockId(BLOCK_TEAMLEAD)
                .optional(true)
                .label(plainText("Relevant teamlead"))
                .element(staticSelect(s -> {
                    s.actionId("value").placeholder(plainText("Pick a teamlead"))
                            .options(teamleadOptions(teamleads));
                    if (ai != null && ai.relevantTeamleadUuid() != null && ai.teamleadName() != null) {
                        s.initialOption(option(plainText(ai.teamleadName()), ai.relevantTeamleadUuid()));
                    }
                    return s;
                }))
                .hint(plainText(aiHint(ai == null ? null : ai.rationales().teamlead(),
                        "Which teamlead should have a first look at this profile?")))));
        blocks.add(context(c -> c.elements(asContextElements(markdownText(
                "Attaching to a position and partner-track sponsorship happen in the intranet — "
                        + "use *View in intranet* on the referral message if you need those.")))));

        return view(v -> v
                .type("modal")
                .callbackId(TRIAGE_CREATE_SUBMIT)
                .privateMetadata(privateMetadata)
                .title(viewTitle(t -> t.type("plain_text").text("Create candidate")))
                .submit(viewSubmit(s -> s.type("plain_text").text("Create candidate")))
                .close(viewClose(c -> c.type("plain_text").text("Cancel")))
                .blocks(blocks));
    }

    /** A current teamlead the triage modal can route to. */
    public record TeamleadOption(String uuid, String name) {
    }

    public static View candidateCreatedView(String candidateName, String candidateUuid,
                                            String baseUrl) {
        return confirmationView("Candidate created", asBlocks(
                section(s -> s.text(markdownText(
                        ":white_check_mark: *" + SlackCandidateFacts.mrkdwnSafe(candidateName)
                                + "* is now a candidate (source: Referral)."))),
                section(s -> s.text(markdownText(
                        "<" + baseUrl + "/recruitment/candidates/" + candidateUuid
                                + "|Open the candidate profile> to attach a position, add notes "
                                + "or start the pipeline."))),
                context(c -> c.elements(asContextElements(markdownText(
                        "The referral is closed and the referrer gets milestone updates "
                                + "automatically from here on."))))));
    }

    /** The dismiss confirmation modal — reason required, consequences stated. */
    public static View triageDismissModal(RecruitmentReferral referral, String referrerName,
                                          String privateMetadata) {
        return view(v -> v
                .type("modal")
                .callbackId(TRIAGE_DISMISS_SUBMIT)
                .privateMetadata(privateMetadata)
                .title(viewTitle(t -> t.type("plain_text").text("Dismiss referral")))
                .submit(viewSubmit(s -> s.type("plain_text").text("Dismiss")))
                .close(viewClose(c -> c.type("plain_text").text("Cancel")))
                .blocks(asBlocks(
                        section(s -> s.text(markdownText(
                                "Dismissing *" + SlackCandidateFacts.mrkdwnSafe(referral.getCandidateName())
                                        + "*'s referral (from "
                                        + SlackCandidateFacts.mrkdwnSafe(referrerName) + ").\n"
                                        + "This closes the referral for good — the referrer sees it as "
                                        + "closed under My referrals, but is *not* shown the reason."))),
                        input(i -> i
                                .blockId(BLOCK_DISMISS_REASON)
                                .label(plainText("Reason"))
                                .element(staticSelect(s -> s.actionId("value")
                                        .placeholder(plainText("Pick the closest match"))
                                        .options(dismissOptions())))
                                .hint(plainText("Recorded on the referral for reporting — "
                                        + "pick the closest match.")))
                )));
    }

    public static View referralDismissedView(String reasonLabel) {
        return confirmationView("Referral dismissed", asBlocks(
                section(s -> s.text(markdownText(
                        ":no_entry_sign: The referral is closed (reason: " + reasonLabel + ")."))),
                context(c -> c.elements(asContextElements(markdownText(
                        "A dismissed referral can't be reopened from Slack — if it was a mistake, "
                                + "create the candidate manually in the intranet."))))));
    }

    // ==========================================================================
    // Capture — "Log to candidate timeline" message shortcut
    // ==========================================================================

    public static View captureModal(String messageText, String privateMetadata) {
        return view(v -> v
                .type("modal")
                .callbackId(CAPTURE_SUBMIT)
                .privateMetadata(privateMetadata)
                .title(viewTitle(t -> t.type("plain_text").text("Log to timeline")))
                .submit(viewSubmit(s -> s.type("plain_text").text("Save note")))
                .close(viewClose(c -> c.type("plain_text").text("Cancel")))
                .blocks(asBlocks(
                        section(s -> s.text(markdownText(
                                "Save this Slack message as a note on a candidate's timeline — "
                                        + "so hallway knowledge lands on the record where the whole "
                                        + "hiring team can find it."))),
                        input(i -> i
                                .blockId(BLOCK_CAPTURE_CANDIDATE)
                                .label(plainText("Candidate"))
                                .element(externalSelect(e -> e
                                        .actionId(CAPTURE_CANDIDATE_SELECT)
                                        .minQueryLength(2)
                                        .placeholder(plainText("Start typing a name…"))))
                                .hint(plainText("You'll only see candidates you have access to. "
                                        + "Missing someone? You may not be involved in their process."))),
                        input(i -> i
                                .blockId(BLOCK_NOTE_TEXT)
                                .label(plainText("Note"))
                                .element(withInitial(
                                        plainTextInput(t -> t.actionId("value").multiline(true)
                                                .maxLength(NOTE_MAX_LENGTH)),
                                        clampNote(messageText)))
                                .hint(plainText("Prefilled with the Slack message — edit freely. "
                                        + "A link back to the original message is saved with the note."))),
                        input(i -> i
                                .blockId(BLOCK_NOTE_PRIVATE)
                                .optional(true)
                                .label(plainText("Visibility"))
                                .element(checkboxes(c -> c.actionId("value")
                                        .options(List.of(option(o -> o
                                                .text(plainText("Private note"))
                                                .value(PRIVATE_OPTION)
                                                .description(plainText("Only you, recruiters and admins "
                                                        + "can read it — other interviewers can't.")))))))
                                .hint(plainText("Leave unticked for a normal note the involved "
                                        + "hiring team can see.")))
                )));
    }

    public static View noteSavedView(String candidateName, String candidateUuid,
                                     String baseUrl, boolean isPrivate) {
        return confirmationView("Note saved", asBlocks(
                section(s -> s.text(markdownText(
                        ":white_check_mark: Note added to *"
                                + SlackCandidateFacts.mrkdwnSafe(candidateName) + "*'s timeline"
                                + (isPrivate ? " (private)" : "") + "."))),
                section(s -> s.text(markdownText(
                        "<" + baseUrl + "/recruitment/candidates/" + candidateUuid
                                + "|Open the timeline>"))),
                context(c -> c.elements(asContextElements(markdownText(
                        "The note links back to the original Slack message for context."))))));
    }

    // ==========================================================================
    // Shared / error views
    // ==========================================================================

    /**
     * A terminal outcome view for conflicts and errors that are not tied to
     * a single input (already triaged, referral gone, no permission) — the
     * modal swaps to this instead of leaving the user stuck on a form that
     * can never submit.
     */
    public static View outcomeView(String title, String mrkdwn) {
        return confirmationView(title, asBlocks(
                section(s -> s.text(markdownText(mrkdwn)))));
    }

    private static View confirmationView(String title, List<LayoutBlock> blocks) {
        return view(v -> v
                .type("modal")
                .title(viewTitle(t -> t.type("plain_text").text(title)))
                .close(viewClose(c -> c.type("plain_text").text("Done")))
                .blocks(blocks));
    }

    // ---- Options & helpers ----------------------------------------------------

    private static List<OptionObject> relationOptions() {
        return List.of(
                option(plainText("Current colleague"), RecruitmentReferralRelation.COLLEAGUE.name()),
                option(plainText("Former colleague"), RecruitmentReferralRelation.FORMER_COLLEAGUE.name()),
                option(plainText("External partner"), RecruitmentReferralRelation.EXTERNAL_PARTNER.name()),
                option(plainText("Other"), RecruitmentReferralRelation.OTHER.name()));
    }

    private static List<OptionObject> dismissOptions() {
        return List.of(
                option(plainText("Duplicate — already in the system"),
                        RecruitmentReferralClosedReason.DUPLICATE.name()),
                option(plainText("Not relevant — no matching need"),
                        RecruitmentReferralClosedReason.NOT_RELEVANT.name()),
                option(plainText("Other"), RecruitmentReferralClosedReason.OTHER.name()));
    }

    /** Human label for a dismiss reason (confirmation copy). */
    public static String dismissReasonLabel(String reasonName) {
        return switch (reasonName) {
            case "DUPLICATE" -> "Duplicate — already in the system";
            case "NOT_RELEVANT" -> "Not relevant — no matching need";
            default -> "Other";
        };
    }

    private static List<OptionObject> experienceOptions() {
        return List.of(
                experienceOption(CandidateExperienceLevel.GRADUATE.name()),
                experienceOption(CandidateExperienceLevel.JUNIOR.name()),
                experienceOption(CandidateExperienceLevel.MID.name()),
                experienceOption(CandidateExperienceLevel.SENIOR.name()),
                experienceOption(CandidateExperienceLevel.PRINCIPAL.name()));
    }

    private static OptionObject experienceOption(String levelName) {
        String label = switch (levelName) {
            case "GRADUATE" -> "Graduate — fresh out of studies";
            case "JUNIOR" -> "Junior — a few years in";
            case "MID" -> "Mid — solid independent profile";
            case "SENIOR" -> "Senior — leads work and people";
            case "PRINCIPAL" -> "Principal — shapes the field";
            default -> levelName;
        };
        return option(plainText(label), levelName);
    }

    private static List<OptionObject> teamleadOptions(List<TeamleadOption> teamleads) {
        List<OptionObject> options = new ArrayList<>(teamleads.size());
        // Slack caps a static select at 100 options — comfortably above the
        // real teamlead count; truncate defensively rather than erroring.
        for (TeamleadOption teamlead : teamleads.stream().limit(100).toList()) {
            options.add(option(plainText(teamlead.name()), teamlead.uuid()));
        }
        return options;
    }

    /**
     * The AI-suggestions context line: which fields were prefilled and the
     * practice suggestion (context-only — the triage command has no practice
     * field; the recruiter applies it when attaching a position later).
     * Rationales are model output over employee-typed referral text —
     * mrkdwn-escaped like any untrusted string.
     */
    private static String aiContextLine(PendingReferralAiSuggestions ai) {
        StringBuilder sb = new StringBuilder(":robot_face: *AI suggestions* prefilled below — verify before creating.");
        if (ai.practiceName() != null) {
            sb.append("\nSuggested practice: *").append(SlackCandidateFacts.mrkdwnSafe(ai.practiceName()))
                    .append('*');
            if (ai.rationales().practice() != null) {
                sb.append(" — ").append(SlackCandidateFacts.mrkdwnSafe(ai.rationales().practice()));
            }
            sb.append(" (applied when you attach a position in the intranet).");
        }
        return sb.toString();
    }

    /** Hint text preferring the AI rationale (plain text — no escaping needed). */
    private static String aiHint(String rationale, String fallback) {
        return rationale == null || rationale.isBlank() ? fallback : "AI: " + rationale;
    }

    /**
     * First-token / rest split of the referral's single name field — a
     * prefill the recruiter is explicitly told to check, never a fact.
     */
    static String[] splitName(String fullName) {
        String trimmed = fullName == null ? "" : fullName.trim();
        int firstSpace = trimmed.indexOf(' ');
        if (firstSpace < 0) {
            return new String[]{trimmed, ""};
        }
        return new String[]{trimmed.substring(0, firstSpace), trimmed.substring(firstSpace + 1).trim()};
    }

    /** Clamp the captured message to the note input's max length. */
    static String clampNote(String messageText) {
        if (messageText == null) {
            return "";
        }
        return messageText.length() <= NOTE_MAX_LENGTH ? messageText
                : messageText.substring(0, NOTE_MAX_LENGTH - 1) + "…";
    }

    /** Sets an initial value only when present — Slack rejects empty strings. */
    private static com.slack.api.model.block.element.PlainTextInputElement withInitial(
            com.slack.api.model.block.element.PlainTextInputElement element, String initialValue) {
        if (initialValue != null && !initialValue.isBlank()) {
            element.setInitialValue(initialValue);
        }
        return element;
    }

    /** response_action error map anchored to a single block. */
    public static Map<String, String> errorOn(String blockId, String message) {
        return Map.of(blockId, message);
    }
}
