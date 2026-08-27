package dk.trustworks.intranet.recruitmentservice.model;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * The closed fact vocabulary of the hiring ledger (Interview Room design
 * spec 2026-08-26 §4.2) — the objective things a hire depends on: notice
 * period, salary expectation and its components, start dates, competing
 * processes, references, practicalities. Facts are {@code NOTE_ADDED}
 * events with {@code payload.field} carrying one of these keys and the
 * value exclusively in {@code pii} (spec §3.3 — zero new anonymisation
 * targets, the entire reason decision 3 chose this shape).
 * <p>
 * Three definitions of this list exist and a contract test on each side
 * asserts they are identical (spec §4.2): this class, the BFF route's
 * {@code NOTE_FIELDS}, and the shared TypeScript union in
 * {@code lib/types/recruitment.ts}.
 * <p>
 * <b>Deliberately absent, permanently:</b> family, health, partner,
 * pregnancy, politics, religion, age. Candidates volunteer these in
 * interviews constantly; a field would convert an offhand remark into
 * structured, queryable, discriminatory data. The absence is the design
 * (spec §4.2) — adding to this list requires a named consumer (a contract
 * placeholder, an offer decision), never "it might be useful".
 */
public final class RecruitmentFactVocabulary {

    private RecruitmentFactVocabulary() {
    }

    /**
     * Who is expected to <em>raise</em> a fact (spec §7.1 whose-job
     * markers). Guidance for the prep lanes, never a permission — anyone
     * entitled to write a fact may write it if the candidate volunteers it.
     */
    public enum AskRole {
        /** Recruiter / hiring owner conversation — never a technical interviewer's lane. */
        RECRUITER,
        /** The hiring owner's conversation (references, commitments). */
        HIRING_OWNER,
        /** Any assigned interviewer may naturally raise it. */
        ANY_INTERVIEWER
    }

    /**
     * The five groups. Freshness windows (spec §4.3): Competition 14 days,
     * Timing 30, Compensation 60, References and Practicalities never
     * ({@code null}). Visibility per group is spec §7.1: Compensation is
     * comp-tier, Competition is the candidate boundary (and NEVER a Slack
     * notification — §7.2, a separate mechanism), the rest hiring tier.
     */
    public enum FactGroup {
        COMPENSATION(60),
        TIMING(30),
        COMPETITION(14),
        REFERENCES(null),
        PRACTICALITIES(null);

        private final Integer freshnessDays;

        FactGroup(Integer freshnessDays) {
            this.freshnessDays = freshnessDays;
        }

        /** Days before a STATED fact reads as STALE; null = never stale. */
        public Integer freshnessDays() {
            return freshnessDays;
        }
    }

    /**
     * One vocabulary entry.
     *
     * @param key                the {@code payload.field} value, persisted verbatim
     * @param group              visibility + staleness group
     * @param label              short human label (ledger cells, gap strip)
     * @param askRole            whose job it is to raise it (guidance, not permission)
     * @param placeholderAliases substrings matched (case-insensitively) against the
     *                           dossier template's placeholder keys — a fact is
     *                           <em>required</em> when the position's contract template
     *                           needs it (spec §4.3 derived completeness; no second
     *                           hand-maintained list), <em>useful</em> otherwise
     * @param defaultRequired    required even before any dossier exists — the facts no
     *                           offer conversation can start without
     */
    public record FactField(String key, FactGroup group, String label, AskRole askRole,
                            List<String> placeholderAliases, boolean defaultRequired) {
    }

    private static final List<FactField> FIELDS = List.of(
            // --- Compensation (comp tier; 60-day window) ----------------------
            new FactField("SALARY_EXPECTATION", FactGroup.COMPENSATION,
                    "Salary expectation", AskRole.RECRUITER,
                    List.of("SALARY", "LOEN", "LØN"), true),
            new FactField("SALARY_COMPONENTS", FactGroup.COMPENSATION,
                    "Package components", AskRole.RECRUITER,
                    List.of("PENSION", "BONUS"), false),
            new FactField("CURRENT_PACKAGE", FactGroup.COMPENSATION,
                    "Current package", AskRole.RECRUITER,
                    List.of(), false),
            // --- Timing (hiring tier; 30-day window) --------------------------
            new FactField("NOTICE_PERIOD", FactGroup.TIMING,
                    "Notice period", AskRole.ANY_INTERVIEWER,
                    List.of("NOTICE"), true),
            new FactField("EARLIEST_START", FactGroup.TIMING,
                    "Earliest start", AskRole.ANY_INTERVIEWER,
                    List.of("START_DATE", "STARTDATE"), true),
            new FactField("PREFERRED_START", FactGroup.TIMING,
                    "Preferred start", AskRole.ANY_INTERVIEWER,
                    List.of(), false),
            new FactField("HARD_DATES", FactGroup.TIMING,
                    "Hard dates", AskRole.ANY_INTERVIEWER,
                    List.of(), false),
            // --- Competition (candidate boundary; 14-day window; NEVER Slack) -
            new FactField("OTHER_PROCESSES", FactGroup.COMPETITION,
                    "Other processes", AskRole.RECRUITER,
                    List.of(), false),
            new FactField("DECISION_DRIVERS", FactGroup.COMPETITION,
                    "Decision drivers", AskRole.RECRUITER,
                    List.of(), false),
            new FactField("DECISION_DATE", FactGroup.COMPETITION,
                    "Decision date", AskRole.RECRUITER,
                    List.of(), false),
            // --- References (hiring tier; never stale) ------------------------
            new FactField("INTERNAL_REFERENCE", FactGroup.REFERENCES,
                    "Internal reference", AskRole.HIRING_OWNER,
                    List.of(), false),
            new FactField("EXTERNAL_REFERENCE", FactGroup.REFERENCES,
                    "External reference", AskRole.HIRING_OWNER,
                    List.of(), false),
            new FactField("REFERENCE_TAKEN", FactGroup.REFERENCES,
                    "Reference taken", AskRole.HIRING_OWNER,
                    List.of(), false),
            // --- Practicalities (hiring tier; never stale) --------------------
            new FactField("LOCATION_CONSTRAINTS", FactGroup.PRACTICALITIES,
                    "Location & travel", AskRole.ANY_INTERVIEWER,
                    List.of(), false),
            new FactField("WORK_PERMIT", FactGroup.PRACTICALITIES,
                    "Work permit", AskRole.ANY_INTERVIEWER,
                    List.of("PERMIT"), false));

    private static final Map<String, FactField> BY_KEY = FIELDS.stream()
            .collect(Collectors.toMap(FactField::key, f -> f, (a, b) -> a, LinkedHashMap::new));

    /** All fields in ledger order. */
    public static List<FactField> all() {
        return FIELDS;
    }

    /** All keys in ledger order — the wire-format validation list. */
    public static Set<String> keys() {
        return BY_KEY.keySet();
    }

    /** @return the entry for a key, empty for anything outside the vocabulary. */
    public static Optional<FactField> forKey(String key) {
        return Optional.ofNullable(key).map(BY_KEY::get);
    }

    /** Whether the key is in the vocabulary at all. */
    public static boolean isKnown(String key) {
        return key != null && BY_KEY.containsKey(key);
    }

    /** Comp-tier gate: the whole compensation group, not just the salary cell. */
    public static boolean isCompScoped(String key) {
        return forKey(key).map(f -> f.group() == FactGroup.COMPENSATION).orElse(false);
    }

    /** Notification exclusion (spec §7.2): competition facts never reach a channel. */
    public static boolean isCompetitionScoped(String key) {
        return forKey(key).map(f -> f.group() == FactGroup.COMPETITION).orElse(false);
    }

    /**
     * Whether a dossier placeholder key makes this fact <em>required</em> —
     * case-insensitive substring match against the field's aliases. Adding a
     * placeholder to a template updates the checklist by itself (spec §4.3).
     */
    public static boolean requiredByPlaceholder(FactField field, String placeholderKey) {
        if (placeholderKey == null || field.placeholderAliases().isEmpty()) {
            return false;
        }
        String upper = placeholderKey.toUpperCase(java.util.Locale.ROOT);
        return field.placeholderAliases().stream().anyMatch(upper::contains);
    }
}
