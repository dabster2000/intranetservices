package dk.trustworks.intranet.recruitmentservice.notifications;

import dk.trustworks.intranet.recruitmentservice.model.RecruitmentApplication;
import dk.trustworks.intranet.recruitmentservice.model.RecruitmentCandidate;
import dk.trustworks.intranet.recruitmentservice.model.RecruitmentPosition;

/**
 * The ONLY candidate data a Slack message builder may consume — the P12
 * PII boundary by construction (plan §P12, Slack companion spec §2.2).
 * <p>
 * Every field is a structural column or an enum code: name (the
 * {@code first_name}/{@code last_name} columns), position title, stage
 * code, source enum, uuids for deep links. Free text — notes, referral
 * why-text, application answers, scorecard prose, salary expectations —
 * has no field here and therefore cannot reach a Slack block. The single
 * permitted free-text exception is the AI brief, which
 * {@link RecruitmentSlackReactor} appends explicitly under its own toggle
 * (findings §P9 carry-over) — it never travels through this record.
 * <p>
 * Later Slack phases (P22 living cards etc.) must build their messages
 * from this record too — extend it with more structural fields, never
 * with free text.
 */
public record SlackCandidateFacts(
        String candidateUuid,
        String candidateName,
        String positionUuid,
        String positionTitle,
        String stageCode,
        String sourceCode,
        String practiceUuid) {

    /** Assemble from loaded entities; every argument may be null. */
    public static SlackCandidateFacts of(RecruitmentCandidate candidate,
                                         RecruitmentPosition position,
                                         RecruitmentApplication application) {
        return new SlackCandidateFacts(
                candidate == null ? null : candidate.getUuid(),
                candidate == null ? null
                        : mrkdwnSafe(joinName(candidate.getFirstName(), candidate.getLastName())),
                position == null ? null : position.getUuid(),
                position == null ? null : mrkdwnSafe(position.getTitle()),
                application == null || application.getStage() == null
                        ? null : application.getStage().name(),
                candidate == null || candidate.getSource() == null
                        ? null : candidate.getSource().name(),
                position == null ? null : position.getPracticeUuid());
    }

    /** The facts of a not-yet-triaged referral: name column only. */
    public static SlackCandidateFacts ofReferralName(String candidateNameColumn) {
        return new SlackCandidateFacts(null, mrkdwnSafe(candidateNameColumn),
                null, null, null, null, null);
    }

    /**
     * Slack mrkdwn control-character escaping (&amp;, &lt;, &gt; — the
     * Slack API escaping rules). Names and titles are user/applicant input;
     * without this a public-form applicant named
     * {@code <https://evil.example|Click here>} would render as a live
     * link in the recruitment channel. Apply to EVERY free-position string
     * interpolated into a Slack message.
     */
    public static String mrkdwnSafe(String s) {
        return s == null ? null
                : s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;");
    }

    /**
     * {@code LINKEDIN_AD} → {@code Linkedin ad} — enum codes never reach
     * Slack raw. Shared by every Slack builder (P12 flat pings, P22 cards).
     */
    public static String humanizeCode(String enumName) {
        String lower = enumName.toLowerCase().replace('_', ' ');
        return Character.toUpperCase(lower.charAt(0)) + lower.substring(1);
    }

    /** Display name with a neutral fallback so a builder never prints "null". */
    public String displayName() {
        return candidateName == null || candidateName.isBlank() ? "Unknown candidate" : candidateName;
    }

    private static String joinName(String first, String last) {
        String name = ((first == null ? "" : first) + " " + (last == null ? "" : last)).trim();
        return name.isEmpty() ? null : name;
    }
}
