package dk.trustworks.intranet.recruitmentservice.airtable;

import dk.trustworks.intranet.recruitmentservice.model.enums.CandidateEducationLevel;
import dk.trustworks.intranet.recruitmentservice.model.enums.CandidateExperienceLevel;
import dk.trustworks.intranet.recruitmentservice.model.enums.CandidateSecurityClearance;
import dk.trustworks.intranet.recruitmentservice.model.enums.CandidateSource;
import dk.trustworks.intranet.recruitmentservice.model.enums.RecruitmentStage;

import java.time.LocalDate;
import java.util.List;
import java.util.Map;

/**
 * One Airtable record normalized to the new model — the output of
 * {@link AirtableFieldMapper}, the input of the import job. Pure data:
 * mapping problems are carried as {@link #warnings} (imported anyway,
 * listed in the report), {@link #blockers} (must be resolved before a
 * real import) or a {@link Disposition#SKIP} (unusable record, imported
 * as nothing, listed skipped-with-reason).
 */
public record AirtableMappedRecord(
        String airtableRecordId,
        String airtableTable,

        // ---- candidate fields (Appendix A.1) ----
        String firstName,
        String lastName,
        String email,
        String phone,
        String linkedinUrl,
        CandidateEducationLevel educationLevel,
        String educationOther,
        CandidateExperienceLevel experienceLevel,
        List<String> specializations,
        CandidateSecurityClearance securityClearance,
        Boolean securityRelevant,
        CandidateSource source,
        Map<String, Object> sourceDetail,
        /** Raw "Reference i Trustworks (navn)" — user resolution happens at import. */
        String referrerName,
        /** Collaborator email of "Relevant team lead" when present. */
        String relevantTeamleadEmail,
        LocalDate createdDate,
        LocalDate lastStatusChange,

        // ---- disposition (Appendix A.2) ----
        Disposition disposition,
        /** Stage for OPEN/HIRED dispositions; {@code null} otherwise. */
        RecruitmentStage stage,
        /** Airtable "Decision needed" / "Need review" pseudo-status → open recruiter task. */
        boolean needsReviewTask,
        String airtableStatus,
        LocalDate expectedStartDate,

        // ---- relations ----
        /** Raw "Hvilken faglighed ansøger du til?" value. */
        String faglighedValue,
        /** Resolved via the practice-mapping config table; {@code null} = unmapped. */
        String practiceUuid,
        boolean consentGranted,
        List<MappedInterview> interviews,
        /** question_key → answer text (WHY_TRUSTWORKS, DNA_MATCH, BEST_TASKS, STRENGTHS). */
        Map<String, String> answers,
        /** Labeled note texts (interview notes, remarks, applicant comment). */
        List<String> notes,
        List<MappedAttachment> attachments,

        // ---- reconciliation ----
        List<String> warnings,
        List<String> blockers,
        String skipReason,
        /** The verbatim Airtable fields — the NOTE_ADDED snapshot (pii). */
        Map<String, Object> rawFields) {

    public enum Disposition {
        /** Open application at {@link #stage()}. */
        OPEN,
        /** Application at stage HIRED, candidate HIRED (Hired + Hired Employees buckets). */
        HIRED,
        /** Terminal REJECTED application (No hire). */
        REJECTED,
        /** Talent pool — candidate only, no application (Backlog). */
        POOLED,
        /** Unusable record — imported as nothing, listed skipped-with-reason. */
        SKIP
    }

    /** An interview derived from the Airtable date columns. */
    public record MappedInterview(boolean informal, Integer round, LocalDate date) {
    }

    /** An Airtable attachment scheduled for S3 (bytes fetched at import). */
    public record MappedAttachment(String kind, String filename, String url, int sizeBytes) {
    }
}
