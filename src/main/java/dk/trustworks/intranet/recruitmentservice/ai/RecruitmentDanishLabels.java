package dk.trustworks.intranet.recruitmentservice.ai;

import dk.trustworks.intranet.recruitmentservice.model.enums.CandidateSource;
import dk.trustworks.intranet.recruitmentservice.model.enums.RecruitmentApplicationTerminal;
import dk.trustworks.intranet.recruitmentservice.model.enums.RecruitmentHiringTrack;
import dk.trustworks.intranet.recruitmentservice.model.enums.RecruitmentStage;

import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;

/**
 * Danish display labels for the enum codes that reach a human — the
 * digest prompt and the Slack message.
 *
 * <h3>Why this exists</h3>
 * Before this class the digest prompt handed the model bare codes
 * ({@code INTERVIEW_1}, {@code PRACTICE_TEAM}, {@code LINKEDIN_SEARCH})
 * with no legend, so the model guessed: sometimes it rendered them as
 * Danish ("på partnerniveau"), sometimes it leaked the raw code into the
 * prose ("fra INTERVIEW_1 til INTERVIEW_2"). Worse, because sources and
 * hiring tracks were listed in the same anonymous "code, count" shape,
 * the model described the hiring track {@code PRACTICE_TEAM} as a
 * <em>source</em>. Translation belongs in code, where it is total and
 * testable, not in a model's guess.
 *
 * <h3>Totality is enforced</h3>
 * {@code RecruitmentDanishLabelsTest} iterates every constant of all four
 * enums and fails if any lacks a label, so adding an enum value without a
 * Danish word breaks the build rather than shipping a raw code to Slack.
 *
 * <h3>Unknown codes</h3>
 * {@link #label(String)} never throws and never returns null. An
 * unrecognised code (a value written by an older image, or the {@code ''}
 * "not applicable" sentinel of the V449/V523 projections) is returned
 * humanised rather than raw, so the worst case is an inelegant label, not
 * a leaked constant.
 */
public final class RecruitmentDanishLabels {

    /** Shown where a projection row carries the '' not-applicable sentinel. */
    public static final String UNKNOWN = "Ukendt";

    private static final Map<String, String> LABELS = new LinkedHashMap<>();

    static {
        // --- Pipeline stages (RecruitmentStage) -----------------------
        LABELS.put(RecruitmentStage.SCREENING.name(), "Screening");
        LABELS.put(RecruitmentStage.INTERVIEW_1.name(), "1. samtale");
        LABELS.put(RecruitmentStage.INTERVIEW_2.name(), "2. samtale");
        LABELS.put(RecruitmentStage.INTERVIEW_3.name(), "3. samtale");
        LABELS.put(RecruitmentStage.OFFER.name(), "Tilbud");
        LABELS.put(RecruitmentStage.HIRED.name(), "Ansat");

        // --- Candidate sources (CandidateSource) ----------------------
        LABELS.put(CandidateSource.REFERRAL.name(), "Henvisning");
        LABELS.put(CandidateSource.PARTNER_REFERRAL.name(), "Partnerhenvisning");
        LABELS.put(CandidateSource.LINKEDIN_SEARCH.name(), "LinkedIn (opsøgt)");
        LABELS.put(CandidateSource.LINKEDIN_AD.name(), "LinkedIn-annonce");
        LABELS.put(CandidateSource.WEBSITE.name(), "Hjemmesiden");
        LABELS.put(CandidateSource.JOBINDEX.name(), "Jobindex");
        LABELS.put(CandidateSource.SOME.name(), "Sociale medier");
        LABELS.put(CandidateSource.CONFERENCE.name(), "Konference");
        LABELS.put(CandidateSource.TW_EVENT.name(), "Trustworks-event");
        LABELS.put(CandidateSource.OTHER.name(), "Andet");

        // --- Hiring tracks (RecruitmentHiringTrack) -------------------
        // NOTE: PRACTICE_TEAM collides in spirit with nothing in
        // CandidateSource, but the two vocabularies must stay visibly
        // distinct in the prompt — see AiDigestPrompts section headings.
        LABELS.put(RecruitmentHiringTrack.PRACTICE_TEAM.name(), "Praksisteam");
        LABELS.put(RecruitmentHiringTrack.PARTNER.name(), "Partner");
        LABELS.put(RecruitmentHiringTrack.STAFF_ROLE.name(), "Stabsfunktion");

        // --- Terminal outcomes (RecruitmentApplicationTerminal) -------
        LABELS.put(RecruitmentApplicationTerminal.REJECTED.name(), "Afslag");
        LABELS.put(RecruitmentApplicationTerminal.WITHDRAWN.name(), "Trukket tilbage");
        LABELS.put(RecruitmentApplicationTerminal.RETURNED_TO_POOL.name(), "Gemt til talentpuljen");
    }

    private RecruitmentDanishLabels() {
    }

    /**
     * The Danish label for an enum code, or a humanised fallback.
     *
     * @param code an enum constant name, possibly '' or unrecognised
     * @return never null, never the raw SCREAMING_SNAKE code
     */
    public static String label(String code) {
        if (code == null || code.isBlank()) {
            return UNKNOWN;
        }
        String known = LABELS.get(code);
        return known != null ? known : humanise(code);
    }

    /**
     * The prompt form: {@code "1. samtale (INTERVIEW_1)"}. The code stays
     * visible so the model can still reason about identity across
     * sections, but the Danish word is what it has to hand when writing.
     */
    public static String labelWithCode(String code) {
        if (code == null || code.isBlank()) {
            return UNKNOWN;
        }
        return label(code) + " (" + code + ")";
    }

    /** {@code SOME_UNKNOWN_CODE} → {@code Some unknown code}. */
    private static String humanise(String code) {
        String words = code.replace('_', ' ').trim().toLowerCase(Locale.ROOT);
        if (words.isEmpty()) {
            return UNKNOWN;
        }
        return Character.toUpperCase(words.charAt(0)) + words.substring(1);
    }

    /** Exposed for the totality test. */
    static boolean hasLabel(String code) {
        return LABELS.containsKey(code);
    }
}
