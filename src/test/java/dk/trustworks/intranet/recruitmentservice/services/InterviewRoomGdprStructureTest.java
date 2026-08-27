package dk.trustworks.intranet.recruitmentservice.services;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The Interview Room's GDPR wiring, pinned structurally (room spec
 * 2026-08-26 §4.4, binding rules 2–3): {@code recruitment_interview_notes}
 * is anonymisation target FIVE and a DSAR-export leg, and both must exist
 * in the same change set as the table — this test is the reason the table
 * may ship. It fails loudly if the table is present and either leg goes
 * missing in a refactor.
 */
class InterviewRoomGdprStructureTest {

    private static final Path MAIN = Path.of("src/main/java/dk/trustworks/intranet/recruitmentservice");

    private static String source(String relative) {
        try {
            return Files.readString(MAIN.resolve(relative));
        } catch (IOException e) {
            throw new AssertionError("Cannot read " + relative
                    + " — did the class move? The GDPR structure guard must move with it.", e);
        }
    }

    /**
     * Spec §4.4 rule 2 — the equivalent of the single-writer exemption
     * guard: the anonymiser must touch {@code recruitment_interview_notes},
     * and as a DELETE (a draft has no structural value worth scrubbing).
     */
    @Test
    void anonymizer_deletesInterviewNoteDrafts() {
        String anonymizer = source("services/RecruitmentAnonymizerService.java");
        assertTrue(anonymizer.contains("recruitment_interview_notes"),
                "RecruitmentAnonymizerService must reference recruitment_interview_notes — "
                        + "the table exists, so the fifth anonymisation target must too (spec §4.4)");
        assertTrue(anonymizer.contains("DELETE n FROM recruitment_interview_notes"),
                "target five is a DELETE, not a scrub (spec §4.4)");
        assertTrue(anonymizer.contains("interviewNoteDraftsDeleted"),
                "the summary and bookkeeping event must count the deleted drafts");
    }

    /** Spec §4.4 rule 3 — a DSAR export that omits the drafts is defective. */
    @Test
    void dsarExport_includesInterviewNoteDrafts() {
        String export = source("services/RecruitmentDsarExportService.java");
        assertTrue(export.contains("RecruitmentInterviewNote"),
                "the DSAR export must include interview note drafts (spec §4.4)");
        assertTrue(export.contains("interview_note_drafts"),
                "the export JSON must carry the drafts under interview_note_drafts");
    }

    /**
     * The 30-day sweep (spec §4.1): an interviewer who never submits must
     * not leave notes about a candidate lying around indefinitely.
     */
    @Test
    void abandonedDrafts_areSweptAfterThirtyDays() {
        String batchlet = source("jobs/RecruitmentInterviewNoteSweepBatchlet.java");
        assertTrue(batchlet.contains("RETENTION_DAYS = 30"),
                "the sweep window is 30 days after scheduled_at (spec §4.1)");
        assertTrue(batchlet.contains("DELETE n FROM recruitment_interview_notes"),
                "the sweep deletes abandoned drafts");
    }

    /**
     * The projection is NOT a target (spec §4.4): it holds no prose and is
     * rebuilt from an already-anonymised stream — the anonymiser must not
     * grow a dependency on it.
     */
    @Test
    void anonymizer_neverTouchesTheFactStateProjection() {
        String anonymizer = source("services/RecruitmentAnonymizerService.java");
        assertTrue(!anonymizer.contains("recruitment_candidate_fact_state")
                        && !anonymizer.contains("RecruitmentCandidateFactState"),
                "recruitment_candidate_fact_state is deliberately not an anonymisation "
                        + "target (spec §4.4) — it holds no prose and is rebuilt from the stream");
    }
}
