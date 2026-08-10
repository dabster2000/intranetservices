package dk.trustworks.intranet.recruitmentservice.airtable;

import dk.trustworks.intranet.recruitmentservice.model.enums.CandidateEducationLevel;
import dk.trustworks.intranet.recruitmentservice.model.enums.CandidateExperienceLevel;
import dk.trustworks.intranet.recruitmentservice.model.enums.CandidateSecurityClearance;
import dk.trustworks.intranet.recruitmentservice.model.enums.CandidateSource;
import dk.trustworks.intranet.recruitmentservice.model.enums.RecruitmentStage;

import java.time.LocalDate;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * The Appendix A mapping contract as code (ATS P21, spec Appendix A.1/A.2):
 * one raw Airtable record in, one {@link AirtableMappedRecord} out. Pure —
 * no CDI, no database, no I/O — so the whole contract is testable in the
 * DB-free fast tier.
 *
 * <h3>Tolerance rules</h3>
 * <ul>
 *   <li>Field names are matched case-insensitively after whitespace
 *       normalization, with English/Danish aliases where the base has
 *       both (reviewed 2026-07-19).</li>
 *   <li>Select values are matched tolerantly; an unmapped select value is
 *       a per-record WARNING (imported anyway, raw value preserved in the
 *       snapshot note), except <em>status</em> and <em>faglighed</em>
 *       values, which are BLOCKERS — a record with an uninterpretable
 *       status or an unmapped practice must never be silently
 *       mis-imported (plan §P21 DoD: unmapped values block the run).</li>
 *   <li>A record with no usable name is SKIPPED with a reason — the
 *       reconciliation contract is 100% mapped or skipped-with-reason.</li>
 * </ul>
 */
public final class AirtableFieldMapper {

    private AirtableFieldMapper() {
    }

    // ---- Appendix A.2 status → disposition -------------------------------

    /** Statuses whose stage is derived from the newest interview date present. */
    private static final List<String> REVIEW_PSEUDO_STATUSES = List.of("decision needed", "need review");

    // ---- Public entry point ----------------------------------------------

    /**
     * Map one raw record. {@code practiceMapping} is the normalized
     * lookup from {@link AirtablePracticeMapping#lookupMap()}; the table
     * name itself resolves through the same mapping (team pipelines are
     * per-practice).
     */
    public static AirtableMappedRecord map(AirtableClient.AirtableRecord record,
                                           String tableName,
                                           Map<String, String> practiceMapping) {
        Map<String, Object> raw = record.fields() == null ? Map.of() : record.fields();
        Fields fields = new Fields(raw);
        List<String> warnings = new ArrayList<>();
        List<String> blockers = new ArrayList<>();

        // ---- names (skip-with-reason when absent) ----
        String firstName = fields.string("Fornavn", "First name");
        String lastName = fields.string("Efternavn", "Last name");
        if (isBlank(firstName) && isBlank(lastName)) {
            // Airtable's computed Name field as a fallback before skipping.
            String computed = fields.string("Name", "Kandidat", "Navn");
            if (!isBlank(computed)) {
                String[] split = computed.trim().split("\\s+", 2);
                firstName = split[0];
                lastName = split.length > 1 ? split[1] : "";
            }
        }
        String skipReason = null;
        if (isBlank(firstName) && isBlank(lastName)) {
            skipReason = "Record has no name (Fornavn/Efternavn/Name all empty)";
        }
        if (isBlank(firstName)) {
            firstName = "(ukendt)";
        }
        if (isBlank(lastName)) {
            lastName = "";
        }

        // ---- disposition (A.2) ----
        String status = fields.string("Status");
        List<AirtableMappedRecord.MappedInterview> interviews = interviews(fields);
        StatusMapping statusMapping = mapStatus(status, interviews);
        if (statusMapping.blocker() != null) {
            blockers.add(statusMapping.blocker());
        }

        // ---- faglighed → practice (config table, never codes) ----
        String faglighed = fields.string("Hvilken faglighed ansøger du til?", "Faglighed");
        String practiceUuid = null;
        String practiceSource = !isBlank(faglighed) ? faglighed : tableName;
        if (!isBlank(practiceSource)) {
            practiceUuid = practiceMapping.get(AirtablePracticeMapping.normalize(practiceSource));
            if (practiceUuid == null) {
                blockers.add("Unmapped faglighed/pipeline value: '" + practiceSource
                        + "' — add it to the practice mapping table");
            }
        }

        // ---- education (A.1: Uddannelse / Hvis anden uddannelse) ----
        String uddannelse = fields.string("Uddannelse", "Education");
        String andenUddannelse = fields.string("Hvis anden uddannelse", "Other education");
        CandidateEducationLevel educationLevel = mapEducation(uddannelse);
        String educationOther = andenUddannelse;
        if (educationLevel == null && !isBlank(uddannelse)) {
            educationLevel = CandidateEducationLevel.OTHER;
            if (isBlank(educationOther)) {
                educationOther = uddannelse;
            }
            warnings.add("Education value '" + uddannelse + "' not recognized — imported as OTHER");
        }

        // ---- experience ----
        String experience = fields.string("Experience", "Erfaring");
        CandidateExperienceLevel experienceLevel = mapExperience(experience);
        if (experienceLevel == null && !isBlank(experience)) {
            warnings.add("Experience value '" + experience + "' not recognized — left empty");
        }

        // ---- security (Sikkerhedsgodkendelse / Sikkerhed) ----
        String clearance = fields.string("Sikkerhedsgodkendelse", "Security clearance");
        CandidateSecurityClearance securityClearance = mapClearance(clearance);
        if (securityClearance == null && !isBlank(clearance)) {
            warnings.add("Sikkerhedsgodkendelse value '" + clearance + "' not recognized — left empty");
        }
        Boolean securityRelevant = fields.bool("Sikkerhed", "Security relevant");

        // ---- source (Vej til Trustworks + follow-ups → source + source_detail) ----
        String vej = fields.string("Vej til Trustworks", "Source");
        CandidateSource source = mapSource(vej);
        if (source == null) {
            if (!isBlank(vej)) {
                warnings.add("Vej til Trustworks value '" + vej + "' not recognized — imported as OTHER");
            }
            source = CandidateSource.OTHER;
        }
        String referrerName = fields.string("Reference i Trustworks (navn)", "Reference i Trustworks");
        if (!isBlank(referrerName) && source == CandidateSource.OTHER && isBlank(vej)) {
            // A reference name with no source answer is still a referral.
            source = CandidateSource.REFERRAL;
        }
        Map<String, Object> sourceDetail = sourceDetail(fields, vej, referrerName, faglighed, practiceUuid);

        // ---- specializations (three per-practice selects collapse into one list) ----
        List<String> specializations = new ArrayList<>();
        specializations.addAll(fields.strings("IT-Management faglighed"));
        specializations.addAll(fields.strings("Trustworks Management faglighed"));
        specializations.addAll(fields.strings("Trustworks Cybersecurity faglighed"));

        // ---- answers (stable question keys, A.1) ----
        Map<String, String> answers = new LinkedHashMap<>();
        putAnswer(answers, "WHY_TRUSTWORKS", fields.string(
                "Hvorfor ansøgning til Trustworks", "Hvorfor Trustworks?", "Hvorfor Trustworks"));
        putAnswer(answers, "DNA_MATCH", fields.string(
                "Hvilke DNA punkter har størst match", "Hvilke DNA-punkter matcher bedst?"));
        putAnswer(answers, "BEST_TASKS", fields.string(
                "Hvilke opgaver trives du bedste med", "Hvilke opgaver trives du bedst med?"));
        putAnswer(answers, "STRENGTHS", fields.string("Erfaringer og styrker"));

        // ---- notes (migrated free text → NOTE_ADDED at import) ----
        List<String> notes = new ArrayList<>();
        addLabeledNote(notes, "Noter fra interview", fields.string("Noter fra interview"));
        addLabeledNote(notes, "Øvrige bemærkninger", fields.string("Øvrige bemærkninger"));
        addLabeledNote(notes, "Kommentar fra ansøger",
                fields.string("Potential Comment From Applicant"));

        // ---- attachments ----
        List<AirtableMappedRecord.MappedAttachment> attachments = new ArrayList<>();
        attachments.addAll(fields.attachments("CV", "CV"));
        attachments.addAll(fields.attachments("Ansøgning", "COVER_LETTER"));
        attachments.addAll(fields.attachments("Kontrakt", "SIGNED_DOCUMENT"));

        // ---- teamlead / dates / consent ----
        String teamleadEmail = fields.collaboratorEmail("Relevant team lead", "Relevant teamlead");
        LocalDate created = firstNonNull(fields.date("Created", "Oprettet"),
                parseDate(record.createdTime()));
        LocalDate lastStatusChange = fields.date("Sidst ændret status", "Last status change");
        LocalDate expectedStart = fields.date("Ansættelsesdato", "Start date");
        boolean consent = Boolean.TRUE.equals(fields.bool("GDPR Godkendelse", "GDPR Consent"));

        return new AirtableMappedRecord(
                record.id(), tableName,
                trim(firstName), trim(lastName),
                trim(fields.string("Personlig E-mail", "E-mail", "Email")),
                trim(fields.string("Telefonnummer", "Telefon", "Phone")),
                trim(fields.string("LinkedIn", "LinkedIn URL")),
                educationLevel, trim(educationOther), experienceLevel,
                specializations.isEmpty() ? null : specializations,
                securityClearance, securityRelevant,
                source, sourceDetail.isEmpty() ? null : sourceDetail,
                trim(referrerName), trim(teamleadEmail),
                created, lastStatusChange,
                skipReason != null ? AirtableMappedRecord.Disposition.SKIP : statusMapping.disposition(),
                statusMapping.stage(), statusMapping.needsReview(),
                trim(status), expectedStart,
                trim(faglighed), practiceUuid, consent,
                interviews, answers, notes, attachments,
                List.copyOf(warnings), List.copyOf(blockers), skipReason,
                raw);
    }

    // ---- Status mapping (A.2) --------------------------------------------

    private record StatusMapping(AirtableMappedRecord.Disposition disposition,
                                 RecruitmentStage stage, boolean needsReview, String blocker) {
    }

    private static StatusMapping mapStatus(String status,
                                           List<AirtableMappedRecord.MappedInterview> interviews) {
        String normalized = status == null ? "" : status.trim().toLowerCase(Locale.ROOT);
        return switch (normalized) {
            case "", "new", "ny" -> open(RecruitmentStage.SCREENING, false);
            case "first interview", "1. interview" -> open(RecruitmentStage.INTERVIEW_1, false);
            case "second interview", "2. interview" -> open(RecruitmentStage.INTERVIEW_2, false);
            case "third interview", "3. interview" -> open(RecruitmentStage.INTERVIEW_3, false);
            case "contract", "kontrakt" -> open(RecruitmentStage.OFFER, false);
            case "hired", "hired employees", "ansat" -> new StatusMapping(
                    AirtableMappedRecord.Disposition.HIRED, RecruitmentStage.HIRED, false, null);
            case "no hire", "no-hire", "afslag" -> new StatusMapping(
                    AirtableMappedRecord.Disposition.REJECTED, null, false, null);
            case "backlog" -> new StatusMapping(
                    AirtableMappedRecord.Disposition.POOLED, null, false, null);
            default -> {
                if (REVIEW_PSEUDO_STATUSES.contains(normalized)) {
                    // Pseudo-status dissolves into an open recruiter task; the
                    // stage is derived from the newest interview round present.
                    yield open(stageFromInterviews(interviews), true);
                }
                yield new StatusMapping(AirtableMappedRecord.Disposition.SKIP, null, false,
                        "Unknown Airtable status: '" + status + "' — extend the status mapping");
            }
        };
    }

    private static StatusMapping open(RecruitmentStage stage, boolean needsReview) {
        return new StatusMapping(AirtableMappedRecord.Disposition.OPEN, stage, needsReview, null);
    }

    /** Newest round present wins; informal chats never advance the stage. */
    static RecruitmentStage stageFromInterviews(List<AirtableMappedRecord.MappedInterview> interviews) {
        int maxRound = 0;
        for (AirtableMappedRecord.MappedInterview interview : interviews) {
            if (!interview.informal() && interview.round() != null) {
                maxRound = Math.max(maxRound, interview.round());
            }
        }
        return switch (maxRound) {
            case 1 -> RecruitmentStage.INTERVIEW_1;
            case 2 -> RecruitmentStage.INTERVIEW_2;
            case 3 -> RecruitmentStage.INTERVIEW_3;
            default -> RecruitmentStage.SCREENING;
        };
    }

    // ---- Select-value maps (tolerant; unmapped → null + caller warning) ---

    static CandidateEducationLevel mapEducation(String value) {
        if (isBlank(value)) {
            return null;
        }
        String v = value.trim().toLowerCase(Locale.ROOT);
        if (v.contains("stud")) {
            return CandidateEducationLevel.STUDENT;
        }
        if (v.contains("bachelor")) {
            return CandidateEducationLevel.BACHELOR;
        }
        if (v.contains("kandidat") || v.contains("master") || v.startsWith("cand")) {
            return CandidateEducationLevel.MASTER;
        }
        if (v.contains("ph.d") || v.contains("phd")) {
            return CandidateEducationLevel.PHD;
        }
        if (v.contains("anden") || v.contains("other")) {
            return CandidateEducationLevel.OTHER;
        }
        return null;
    }

    static CandidateExperienceLevel mapExperience(String value) {
        if (isBlank(value)) {
            return null;
        }
        String v = value.trim().toLowerCase(Locale.ROOT);
        if (v.contains("graduate") || v.contains("nyuddannet")) {
            return CandidateExperienceLevel.GRADUATE;
        }
        if (v.contains("junior")) {
            return CandidateExperienceLevel.JUNIOR;
        }
        if (v.contains("mid") || v.contains("erfaren")) {
            return CandidateExperienceLevel.MID;
        }
        if (v.contains("senior")) {
            return CandidateExperienceLevel.SENIOR;
        }
        if (v.contains("principal") || v.contains("partner")) {
            return CandidateExperienceLevel.PRINCIPAL;
        }
        return null;
    }

    static CandidateSecurityClearance mapClearance(String value) {
        if (isBlank(value)) {
            return null;
        }
        String v = value.trim().toLowerCase(Locale.ROOT);
        if (v.contains("godkendt") || v.contains("cleared") || v.equals("ja") || v.equals("yes")) {
            return CandidateSecurityClearance.CLEARED;
        }
        if (v.contains("afventer") || v.contains("pending") || v.contains("under behandling")) {
            return CandidateSecurityClearance.PENDING;
        }
        if (v.contains("ingen") || v.contains("none") || v.equals("nej") || v.equals("no")) {
            return CandidateSecurityClearance.NONE;
        }
        return null;
    }

    static CandidateSource mapSource(String value) {
        if (isBlank(value)) {
            return null;
        }
        String v = value.trim().toLowerCase(Locale.ROOT);
        if (v.contains("netværk") || v.contains("network") || v.contains("reference")
                || v.contains("anbefal")) {
            return CandidateSource.REFERRAL;
        }
        if (v.contains("linkedin")) {
            return CandidateSource.LINKEDIN_AD;
        }
        if (v.contains("some") || v.contains("social")) {
            return CandidateSource.SOME;
        }
        if (v.contains("konference") || v.contains("messe") || v.contains("conference")) {
            return CandidateSource.CONFERENCE;
        }
        if (v.contains("trustworks event") || v.contains("tw event") || v.contains("event")) {
            return CandidateSource.TW_EVENT;
        }
        if (v.contains("jobindex")) {
            return CandidateSource.JOBINDEX;
        }
        if (v.contains("hjemmeside") || v.contains("website") || v.contains("jobopslag")
                || v.contains("job listing")) {
            return CandidateSource.WEBSITE;
        }
        return null;
    }

    // ---- source_detail assembly (mirrors the P5 adaptive follow-up keys) --

    private static Map<String, Object> sourceDetail(Fields fields, String vej, String referrerName,
                                                    String faglighed, String practiceUuid) {
        Map<String, Object> detail = new LinkedHashMap<>();
        if (!isBlank(vej)) {
            detail.put("airtableVej", vej.trim());
        }
        put(detail, "airtableAndenVej", fields.string("Anden vej"));
        put(detail, "channel", fields.string("Hvilken SoMe kanal"));
        String conference = firstNonBlank(
                fields.string("På hvilken konference-messe"), fields.string("Which Conference?"));
        String twEvent = firstNonBlank(
                fields.string("På hvilket Trustworks event"), fields.string("Which Trustworks Event?"));
        put(detail, "eventName", firstNonBlank(conference, twEvent));
        put(detail, "jobListingRef", fields.string("Specific Job Listing"));
        put(detail, "referenceName", referrerName);
        if (!isBlank(faglighed) && practiceUuid != null) {
            detail.put("desiredPracticeUuid", practiceUuid);
        }
        return detail;
    }

    // ---- Interview dates → interview rows (A.1) --------------------------

    private static List<AirtableMappedRecord.MappedInterview> interviews(Fields fields) {
        List<AirtableMappedRecord.MappedInterview> interviews = new ArrayList<>();
        LocalDate informal = fields.date("Uformel interview dato", "Uformelt interview dato");
        if (informal != null) {
            interviews.add(new AirtableMappedRecord.MappedInterview(true, null, informal));
        }
        addRound(interviews, 1, fields.date("1. Interview dato", "1. interview dato", "First interview"));
        addRound(interviews, 2, fields.date("2. Interview dato", "2. interview dato", "Second interview"));
        addRound(interviews, 3, fields.date("3. Interview dato", "3. interview dato", "Third interview"));
        return interviews;
    }

    private static void addRound(List<AirtableMappedRecord.MappedInterview> interviews,
                                 int round, LocalDate date) {
        if (date != null) {
            interviews.add(new AirtableMappedRecord.MappedInterview(false, round, date));
        }
    }

    // ---- Tolerant field access -------------------------------------------

    /** Case-insensitive, whitespace-normalized view over the raw fields map. */
    static final class Fields {

        private final Map<String, Object> byNormalizedName = new LinkedHashMap<>();

        Fields(Map<String, Object> raw) {
            for (Map.Entry<String, Object> entry : raw.entrySet()) {
                byNormalizedName.putIfAbsent(normalizeKey(entry.getKey()), entry.getValue());
            }
        }

        private static String normalizeKey(String key) {
            return key == null ? "" : key.trim().replaceAll("\\s+", " ").toLowerCase(Locale.ROOT);
        }

        Object value(String... aliases) {
            for (String alias : aliases) {
                Object value = byNormalizedName.get(normalizeKey(alias));
                if (value != null) {
                    return value;
                }
            }
            return null;
        }

        String string(String... aliases) {
            Object value = value(aliases);
            if (value == null) {
                return null;
            }
            if (value instanceof String s) {
                return s.isBlank() ? null : s;
            }
            if (value instanceof Map<?, ?> map && map.get("name") instanceof String name) {
                // Collaborator / linked-record object.
                return name;
            }
            if (value instanceof List<?> list && !list.isEmpty()) {
                Object first = list.get(0);
                if (first instanceof String s) {
                    return s;
                }
                if (first instanceof Map<?, ?> map && map.get("name") instanceof String name) {
                    return name;
                }
            }
            return String.valueOf(value);
        }

        /** Multi-select (list of strings) or single select as a list. */
        List<String> strings(String... aliases) {
            Object value = value(aliases);
            if (value instanceof List<?> list) {
                List<String> result = new ArrayList<>();
                for (Object item : list) {
                    if (item instanceof String s && !s.isBlank()) {
                        result.add(s.trim());
                    }
                }
                return result;
            }
            if (value instanceof String s && !s.isBlank()) {
                return List.of(s.trim());
            }
            return List.of();
        }

        Boolean bool(String... aliases) {
            Object value = value(aliases);
            if (value == null) {
                return null;
            }
            if (value instanceof Boolean b) {
                return b;
            }
            String s = String.valueOf(value).trim().toLowerCase(Locale.ROOT);
            if (s.isEmpty()) {
                return null;
            }
            return s.equals("true") || s.equals("ja") || s.equals("yes") || s.equals("checked");
        }

        LocalDate date(String... aliases) {
            Object value = value(aliases);
            return value == null ? null : parseDate(String.valueOf(value));
        }

        String collaboratorEmail(String... aliases) {
            Object value = value(aliases);
            if (value instanceof Map<?, ?> map && map.get("email") instanceof String email) {
                return email;
            }
            if (value instanceof List<?> list && !list.isEmpty()
                    && list.get(0) instanceof Map<?, ?> map
                    && map.get("email") instanceof String email) {
                return email;
            }
            return null;
        }

        /** Airtable attachment field → mapped attachments with the given kind. */
        List<AirtableMappedRecord.MappedAttachment> attachments(String fieldName, String kind) {
            Object value = value(fieldName);
            if (!(value instanceof List<?> list)) {
                return List.of();
            }
            List<AirtableMappedRecord.MappedAttachment> result = new ArrayList<>();
            for (Object item : list) {
                if (item instanceof Map<?, ?> map
                        && map.get("url") instanceof String url
                        && !url.isBlank()) {
                    String filename = map.get("filename") instanceof String f && !f.isBlank()
                            ? f : kind.toLowerCase(Locale.ROOT) + ".pdf";
                    int size = map.get("size") instanceof Number n ? n.intValue() : 0;
                    result.add(new AirtableMappedRecord.MappedAttachment(kind, filename, url, size));
                }
            }
            return result;
        }
    }

    // ---- Small helpers ---------------------------------------------------

    static LocalDate parseDate(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        String candidate = value.trim();
        if (candidate.length() >= 10) {
            candidate = candidate.substring(0, 10);
        }
        try {
            return LocalDate.parse(candidate);
        } catch (DateTimeParseException e) {
            return null;
        }
    }

    private static void putAnswer(Map<String, String> answers, String key, String value) {
        if (!isBlank(value)) {
            answers.put(key, value.trim());
        }
    }

    private static void addLabeledNote(List<String> notes, String label, String text) {
        if (!isBlank(text)) {
            notes.add(label + ":\n" + text.trim());
        }
    }

    private static void put(Map<String, Object> map, String key, String value) {
        if (!isBlank(value)) {
            map.put(key, value.trim());
        }
    }

    private static boolean isBlank(String value) {
        return value == null || value.isBlank();
    }

    private static String trim(String value) {
        return value == null ? null : value.trim();
    }

    private static String firstNonBlank(String a, String b) {
        return !isBlank(a) ? a : (!isBlank(b) ? b : null);
    }

    private static <T> T firstNonNull(T a, T b) {
        return a != null ? a : b;
    }
}
