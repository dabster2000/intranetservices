package dk.trustworks.intranet.recruitmentservice.airtable;

import dk.trustworks.intranet.recruitmentservice.model.enums.CandidateEducationLevel;
import dk.trustworks.intranet.recruitmentservice.model.enums.CandidateExperienceLevel;
import dk.trustworks.intranet.recruitmentservice.model.enums.CandidateSecurityClearance;
import dk.trustworks.intranet.recruitmentservice.model.enums.CandidateSource;
import dk.trustworks.intranet.recruitmentservice.model.enums.RecruitmentStage;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import java.time.LocalDate;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The Appendix A migration contract, fixture-tested (plan §P21 DoD:
 * 100% of Airtable records mapped or explicitly listed skipped-with-
 * reason; unmapped faglighed values block; statuses per A.2). DB-free —
 * runs in the fast tier that gates deploys.
 */
class AirtableFieldMapperTest {

    private static final String PM_PRACTICE = "pm-practice-uuid";
    private static final String IT_PRACTICE = "it-practice-uuid";

    private static final Map<String, String> MAPPING = Map.of(
            AirtablePracticeMapping.normalize("Projektledelse"), PM_PRACTICE,
            AirtablePracticeMapping.normalize("IT-Management"), IT_PRACTICE,
            AirtablePracticeMapping.normalize("IT-Management pipeline"), IT_PRACTICE);

    private static AirtableClient.AirtableRecord record(Map<String, Object> fields) {
        return new AirtableClient.AirtableRecord("recABC123", "2024-03-01T10:00:00.000Z", fields);
    }

    // ------------------------------------------------------------------
    // A.1 field mapping
    // ------------------------------------------------------------------

    @Test
    void fullRecord_mapsEveryAppendixA1Field() {
        Map<String, Object> fields = new HashMap<>();
        fields.put("Fornavn", "Jane");
        fields.put("Efternavn", "Doe");
        fields.put("Personlig E-mail", "jane@doe.dk");
        fields.put("Telefonnummer", "+45 12 34 56 78");
        fields.put("LinkedIn", "https://linkedin.com/in/jane-doe");
        fields.put("Status", "Second interview");
        fields.put("Uddannelse", "Kandidat");
        fields.put("Experience", "Senior");
        fields.put("Sikkerhedsgodkendelse", "Godkendt");
        fields.put("Sikkerhed", true);
        fields.put("Vej til Trustworks", "Netværk");
        fields.put("Reference i Trustworks (navn)", "Peter Konsulent");
        fields.put("Hvilken faglighed ansøger du til?", "Projektledelse");
        fields.put("IT-Management faglighed", List.of("Projektleder", "Scrum Master"));
        fields.put("Ansættelsesdato", "2026-10-01");
        fields.put("Sidst ændret status", "2026-08-01");
        fields.put("Created", "2026-06-15");
        fields.put("GDPR Godkendelse", true);
        fields.put("Hvorfor ansøgning til Trustworks", "Fordi kulturen passer mig.");
        fields.put("Erfaringer og styrker", "10 års projektledelse.");
        fields.put("Noter fra interview", "God energi.");
        fields.put("1. Interview dato", "2026-07-01");
        fields.put("2. Interview dato", "2026-07-20");
        fields.put("Uformel interview dato", "2026-06-20");
        fields.put("CV", List.of(Map.of("url", "https://dl.airtable.com/cv.pdf",
                "filename", "jane-cv.pdf", "size", 12345)));
        fields.put("Relevant team lead", Map.of("email", "lead@trustworks.dk", "name", "Team Lead"));

        AirtableMappedRecord mapped = AirtableFieldMapper.map(
                record(fields), "IT-Management pipeline", MAPPING);

        assertEquals("Jane", mapped.firstName());
        assertEquals("Doe", mapped.lastName());
        assertEquals("jane@doe.dk", mapped.email());
        assertEquals("+45 12 34 56 78", mapped.phone());
        assertEquals("https://linkedin.com/in/jane-doe", mapped.linkedinUrl());
        assertEquals(AirtableMappedRecord.Disposition.OPEN, mapped.disposition());
        assertEquals(RecruitmentStage.INTERVIEW_2, mapped.stage());
        assertEquals(CandidateEducationLevel.MASTER, mapped.educationLevel());
        assertEquals(CandidateExperienceLevel.SENIOR, mapped.experienceLevel());
        assertEquals(CandidateSecurityClearance.CLEARED, mapped.securityClearance());
        assertEquals(Boolean.TRUE, mapped.securityRelevant());
        assertEquals(CandidateSource.REFERRAL, mapped.source());
        assertEquals("Peter Konsulent", mapped.referrerName());
        assertEquals(PM_PRACTICE, mapped.practiceUuid());
        assertEquals(List.of("Projektleder", "Scrum Master"), mapped.specializations());
        assertEquals(LocalDate.of(2026, 10, 1), mapped.expectedStartDate());
        assertEquals(LocalDate.of(2026, 8, 1), mapped.lastStatusChange());
        assertEquals(LocalDate.of(2026, 6, 15), mapped.createdDate());
        assertTrue(mapped.consentGranted());
        assertEquals("Fordi kulturen passer mig.", mapped.answers().get("WHY_TRUSTWORKS"));
        assertEquals("10 års projektledelse.", mapped.answers().get("STRENGTHS"));
        assertEquals(1, mapped.notes().size());
        assertTrue(mapped.notes().get(0).contains("God energi."));
        assertEquals("lead@trustworks.dk", mapped.relevantTeamleadEmail());
        // 3 interviews: informal + rounds 1-2
        assertEquals(3, mapped.interviews().size());
        assertTrue(mapped.interviews().stream().anyMatch(AirtableMappedRecord.MappedInterview::informal));
        // attachment
        assertEquals(1, mapped.attachments().size());
        assertEquals("CV", mapped.attachments().get(0).kind());
        assertEquals("jane-cv.pdf", mapped.attachments().get(0).filename());
        // clean record: no blockers, no skip
        assertTrue(mapped.blockers().isEmpty(), () -> "blockers: " + mapped.blockers());
        assertNull(mapped.skipReason());
        // the raw snapshot is carried verbatim for the NOTE_ADDED event
        assertEquals(fields, mapped.rawFields());
    }

    // ------------------------------------------------------------------
    // A.2 status mapping
    // ------------------------------------------------------------------

    @ParameterizedTest
    @CsvSource({
            "New,            OPEN,     SCREENING",
            "First interview, OPEN,    INTERVIEW_1",
            "Second interview, OPEN,   INTERVIEW_2",
            "Third interview, OPEN,    INTERVIEW_3",
            "Contract,       OPEN,     OFFER",
            "Hired,          HIRED,    HIRED",
            "Hired Employees, HIRED,   HIRED",
            "No hire,        REJECTED, ",
            "Backlog,        POOLED,   ",
    })
    void statusMapping_followsAppendixA2(String status, String disposition, String stage) {
        AirtableMappedRecord mapped = AirtableFieldMapper.map(
                record(Map.of("Fornavn", "A", "Efternavn", "B", "Status", status)),
                "IT-Management", MAPPING);
        assertEquals(AirtableMappedRecord.Disposition.valueOf(disposition), mapped.disposition());
        if (stage == null || stage.isBlank()) {
            assertNull(mapped.stage());
        } else {
            assertEquals(RecruitmentStage.valueOf(stage), mapped.stage());
        }
        assertTrue(mapped.blockers().isEmpty());
    }

    @Test
    void emptyStatus_isScreening() {
        AirtableMappedRecord mapped = AirtableFieldMapper.map(
                record(Map.of("Fornavn", "A", "Efternavn", "B")), "IT-Management", MAPPING);
        assertEquals(AirtableMappedRecord.Disposition.OPEN, mapped.disposition());
        assertEquals(RecruitmentStage.SCREENING, mapped.stage());
    }

    @Test
    void unknownStatus_blocksTheRun() {
        AirtableMappedRecord mapped = AirtableFieldMapper.map(
                record(Map.of("Fornavn", "A", "Efternavn", "B", "Status", "Weird status")),
                "IT-Management", MAPPING);
        assertEquals(AirtableMappedRecord.Disposition.SKIP, mapped.disposition());
        assertNull(mapped.skipReason(), "unknown status is a BLOCKER, not a silent skip");
        assertFalse(mapped.blockers().isEmpty());
        assertTrue(mapped.blockers().get(0).contains("Weird status"));
    }

    @Test
    void decisionNeeded_dissolvesIntoReviewTask_stageFromNewestInterview() {
        AirtableMappedRecord mapped = AirtableFieldMapper.map(
                record(Map.of(
                        "Fornavn", "A", "Efternavn", "B",
                        "Status", "Decision needed",
                        "1. Interview dato", "2026-07-01",
                        "2. Interview dato", "2026-07-20")),
                "IT-Management", MAPPING);
        assertEquals(AirtableMappedRecord.Disposition.OPEN, mapped.disposition());
        assertEquals(RecruitmentStage.INTERVIEW_2, mapped.stage());
        assertTrue(mapped.needsReviewTask());
        assertTrue(mapped.blockers().isEmpty());
    }

    @Test
    void needReview_withNoInterviews_landsInScreening() {
        AirtableMappedRecord mapped = AirtableFieldMapper.map(
                record(Map.of("Fornavn", "A", "Efternavn", "B", "Status", "Need review")),
                "IT-Management", MAPPING);
        assertEquals(AirtableMappedRecord.Disposition.OPEN, mapped.disposition());
        assertEquals(RecruitmentStage.SCREENING, mapped.stage());
        assertTrue(mapped.needsReviewTask());
    }

    // ------------------------------------------------------------------
    // Practice mapping (spec §10: config table, never hardcoded codes)
    // ------------------------------------------------------------------

    @Test
    void unmappedFaglighed_blocksWithClearMessage() {
        AirtableMappedRecord mapped = AirtableFieldMapper.map(
                record(Map.of("Fornavn", "A", "Efternavn", "B",
                        "Hvilken faglighed ansøger du til?", "Management Consulting")),
                "IT-Management", MAPPING);
        assertNull(mapped.practiceUuid());
        assertTrue(mapped.blockers().stream()
                .anyMatch(b -> b.contains("Management Consulting")));
    }

    @Test
    void faglighedFallsBackToPipelineTableName() {
        AirtableMappedRecord mapped = AirtableFieldMapper.map(
                record(Map.of("Fornavn", "A", "Efternavn", "B")),
                "IT-Management pipeline", MAPPING);
        assertEquals(IT_PRACTICE, mapped.practiceUuid());
        assertTrue(mapped.blockers().isEmpty());
    }

    @Test
    void mappingIsCaseInsensitive() {
        AirtableMappedRecord mapped = AirtableFieldMapper.map(
                record(Map.of("Fornavn", "A", "Efternavn", "B",
                        "Hvilken faglighed ansøger du til?", "  PROJEKTLEDELSE ")),
                "IT-Management", MAPPING);
        assertEquals(PM_PRACTICE, mapped.practiceUuid());
    }

    // ------------------------------------------------------------------
    // Skips + tolerant values
    // ------------------------------------------------------------------

    @Test
    void namelessRecord_isSkippedWithReason() {
        AirtableMappedRecord mapped = AirtableFieldMapper.map(
                record(Map.of("Status", "New")), "IT-Management", MAPPING);
        assertEquals(AirtableMappedRecord.Disposition.SKIP, mapped.disposition());
        assertNotNull(mapped.skipReason());
    }

    @Test
    void computedNameField_rescuesSplitNames() {
        AirtableMappedRecord mapped = AirtableFieldMapper.map(
                record(Map.of("Name", "Jens Peter Hansen", "Status", "New")),
                "IT-Management", MAPPING);
        assertEquals("Jens", mapped.firstName());
        assertEquals("Peter Hansen", mapped.lastName());
        assertNull(mapped.skipReason());
    }

    @Test
    void unrecognizedEducation_importsAsOtherWithWarning() {
        AirtableMappedRecord mapped = AirtableFieldMapper.map(
                record(Map.of("Fornavn", "A", "Efternavn", "B", "Uddannelse", "Selvlært")),
                "IT-Management", MAPPING);
        assertEquals(CandidateEducationLevel.OTHER, mapped.educationLevel());
        assertEquals("Selvlært", mapped.educationOther());
        assertFalse(mapped.warnings().isEmpty());
        assertTrue(mapped.blockers().isEmpty(), "value warnings never block");
    }

    @Test
    void unrecognizedSource_importsAsOtherAndKeepsRawInSourceDetail() {
        AirtableMappedRecord mapped = AirtableFieldMapper.map(
                record(Map.of("Fornavn", "A", "Efternavn", "B",
                        "Vej til Trustworks", "Noget helt andet")),
                "IT-Management", MAPPING);
        assertEquals(CandidateSource.OTHER, mapped.source());
        assertEquals("Noget helt andet", mapped.sourceDetail().get("airtableVej"));
    }

    @Test
    void referenceNameWithoutSourceAnswer_isStillAReferral() {
        AirtableMappedRecord mapped = AirtableFieldMapper.map(
                record(Map.of("Fornavn", "A", "Efternavn", "B",
                        "Reference i Trustworks (navn)", "Mette Medarbejder")),
                "IT-Management", MAPPING);
        assertEquals(CandidateSource.REFERRAL, mapped.source());
        assertEquals("Mette Medarbejder", mapped.sourceDetail().get("referenceName"));
    }

    @Test
    void fieldNames_matchCaseAndWhitespaceInsensitively() {
        AirtableMappedRecord mapped = AirtableFieldMapper.map(
                record(Map.of("fornavn ", "Jane", "EFTERNAVN", "Doe", "status", "new")),
                "IT-Management", MAPPING);
        assertEquals("Jane", mapped.firstName());
        assertEquals("Doe", mapped.lastName());
        assertEquals(AirtableMappedRecord.Disposition.OPEN, mapped.disposition());
    }

    @Test
    void gdprConsentEnglishAlias_counts() {
        AirtableMappedRecord mapped = AirtableFieldMapper.map(
                record(Map.of("Fornavn", "A", "Efternavn", "B", "GDPR Consent", true)),
                "IT-Management", MAPPING);
        assertTrue(mapped.consentGranted());
    }

    // ------------------------------------------------------------------
    // Retention triage predicate (spec §10 step 4)
    // ------------------------------------------------------------------

    private static AirtableMappedRecord withDisposition(String status, String lastChange,
                                                        boolean consent) {
        Map<String, Object> fields = new HashMap<>();
        fields.put("Fornavn", "A");
        fields.put("Efternavn", "B");
        fields.put("Status", status);
        if (lastChange != null) {
            fields.put("Sidst ændret status", lastChange);
        }
        if (consent) {
            fields.put("GDPR Godkendelse", true);
        }
        return AirtableFieldMapper.map(record(fields), "IT-Management", MAPPING);
    }

    @Test
    void oldRejectedWithoutConsent_isTriage() {
        LocalDate today = LocalDate.of(2026, 8, 9);
        assertTrue(AirtableImportService.isRetentionTriage(
                withDisposition("No hire", "2025-06-01", false), today));
    }

    @Test
    void oldBacklogWithoutConsent_isTriage() {
        LocalDate today = LocalDate.of(2026, 8, 9);
        assertTrue(AirtableImportService.isRetentionTriage(
                withDisposition("Backlog", "2025-06-01", false), today));
    }

    @Test
    void recentRejected_isNotTriage() {
        LocalDate today = LocalDate.of(2026, 8, 9);
        assertFalse(AirtableImportService.isRetentionTriage(
                withDisposition("No hire", "2026-07-01", false), today));
    }

    @Test
    void consentedOldBacklog_isNotTriage() {
        LocalDate today = LocalDate.of(2026, 8, 9);
        assertFalse(AirtableImportService.isRetentionTriage(
                withDisposition("Backlog", "2025-06-01", true), today));
    }

    @Test
    void hiredAndOpen_neverTriage() {
        LocalDate today = LocalDate.of(2026, 8, 9);
        assertFalse(AirtableImportService.isRetentionTriage(
                withDisposition("Hired Employees", "2020-01-01", false), today),
                "HIRED leaves the retention regime entirely");
        assertFalse(AirtableImportService.isRetentionTriage(
                withDisposition("First interview", "2025-01-01", false), today),
                "an open process is active, never triage");
    }

    @Test
    void lastActivity_fallsBackFromStatusChangeToCreated() {
        AirtableMappedRecord withBoth = AirtableFieldMapper.map(record(Map.of(
                        "Fornavn", "A", "Efternavn", "B",
                        "Sidst ændret status", "2026-05-05", "Created", "2026-01-01")),
                "IT-Management", MAPPING);
        assertEquals(LocalDate.of(2026, 5, 5), AirtableImportService.lastActivity(withBoth));

        AirtableMappedRecord createdOnly = AirtableFieldMapper.map(record(Map.of(
                        "Fornavn", "A", "Efternavn", "B", "Created", "2026-01-01")),
                "IT-Management", MAPPING);
        assertEquals(LocalDate.of(2026, 1, 1), AirtableImportService.lastActivity(createdOnly));
    }

    // ------------------------------------------------------------------
    // Date parsing
    // ------------------------------------------------------------------

    @ParameterizedTest
    @CsvSource({
            "2026-07-01,               2026-07-01",
            "2026-07-01T09:30:00.000Z, 2026-07-01",
    })
    void dates_parseWithAndWithoutTime(String raw, String expected) {
        assertEquals(LocalDate.parse(expected), AirtableFieldMapper.parseDate(raw));
    }

    @Test
    void garbageDates_areNullNotErrors() {
        assertNull(AirtableFieldMapper.parseDate("ikke en dato"));
        assertNull(AirtableFieldMapper.parseDate(""));
        assertNull(AirtableFieldMapper.parseDate(null));
    }
}
