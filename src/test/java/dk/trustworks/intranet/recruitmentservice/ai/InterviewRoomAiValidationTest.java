package dk.trustworks.intranet.recruitmentservice.ai;

import dk.trustworks.intranet.recruitmentservice.dto.RoomPrepResponse.PrepSubject;
import dk.trustworks.intranet.recruitmentservice.dto.RoomSuggestResponse;
import dk.trustworks.intranet.recruitmentservice.dto.RoomSuggestResponse.RoomFactSuggestion;
import dk.trustworks.intranet.recruitmentservice.dto.RoomSuggestResponse.RoomSubjectTag;
import dk.trustworks.intranet.recruitmentservice.dto.RoomTidyResponse;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The room's AI output validation (room spec 2026-08-26 §9) — the model
 * is untrusted, and these are the guards that make the oversight contract
 * real: vocabulary-keyed extraction with mandatory evidence, the Tidy gap
 * rule ("writes nothing into a subject that has no lines"), and the prep
 * pack's questions-only contract.
 */
class InterviewRoomAiValidationTest {

    // ---- Extraction (§5.4) ------------------------------------------------

    /** The interview's own scorecard subjects — the only tags that survive. */
    private static final Set<String> SUBJECTS =
            Set.of("CULTURE", "FAGLIGHED", "COMMERCIAL_DRIVE");

    @Test
    void extraction_dropsFieldsOutsideTheVocabulary() {
        String raw = """
                {"suggestions":[
                  {"lineIndex":0,"field":"EARLIEST_START","value":"2026-12-01","evidence":"kan starte 1. dec"},
                  {"lineIndex":1,"field":"PREGNANCY","value":"...","evidence":"..."},
                  {"lineIndex":2,"field":"FAVOURITE_COLOUR","value":"blue","evidence":"likes blue"},
                  {"lineIndex":0,"field":"NOTICE_PERIOD","value":"3 mdr","evidence":"3 mdr opsigelse"}
                ],"subjectTags":[]}""";
        List<RoomFactSuggestion> suggestions =
                InterviewRoomAiService.validateExtraction(raw, SUBJECTS, 3).suggestions();
        assertEquals(1, suggestions.size());
        assertEquals("EARLIEST_START", suggestions.get(0).field());
    }

    /** Retired 2026-08-27 — the model may still name it; the guard drops it. */
    @Test
    void extraction_dropsTheRetiredPracticalitiesAndNoticeFields() {
        String raw = """
                {"suggestions":[
                  {"lineIndex":0,"field":"NOTICE_PERIOD","value":"3 mdr","evidence":"3 mdr opsigelse"},
                  {"lineIndex":0,"field":"WORK_PERMIT","value":"EU","evidence":"EU-borger"},
                  {"lineIndex":0,"field":"LOCATION_CONSTRAINTS","value":"KBH","evidence":"bor i KBH"}
                ],"subjectTags":[]}""";
        assertTrue(InterviewRoomAiService.validateExtraction(raw, SUBJECTS, 1)
                        .suggestions().isEmpty(),
                "the retired fields are no longer in the vocabulary");
    }

    @Test
    void extraction_requiresEvidence() {
        String raw = """
                {"suggestions":[
                  {"lineIndex":0,"field":"EARLIEST_START","value":"2026-12-01","evidence":""},
                  {"lineIndex":0,"field":"PREFERRED_START","value":"2026-12-01"}
                ],"subjectTags":[]}""";
        assertTrue(InterviewRoomAiService.validateExtraction(raw, SUBJECTS, 1)
                        .suggestions().isEmpty(),
                "a proposal without its source quote is dropped (spec §5.4)");
    }

    /**
     * A hallucinated anchor is the shape an out-of-range index takes —
     * letting one through pins a chip to a line the interviewer never wrote.
     */
    @Test
    void extraction_dropsAnchorsOutsideTheCallersLines() {
        String raw = """
                {"suggestions":[
                  {"lineIndex":7,"field":"EARLIEST_START","value":"2026-12-01","evidence":"1. dec"},
                  {"lineIndex":-1,"field":"HARD_DATES","value":"ferie uge 42","evidence":"uge 42"},
                  {"lineIndex":1,"field":"DECISION_DATE","value":"2026-09-15","evidence":"beslutter 15."}
                ],"subjectTags":[
                  {"lineIndex":9,"subjectCode":"CULTURE"},
                  {"lineIndex":0,"subjectCode":"FAGLIGHED"}
                ]}""";
        RoomSuggestResponse response =
                InterviewRoomAiService.validateExtraction(raw, SUBJECTS, 2);
        assertEquals(1, response.suggestions().size());
        assertEquals(1, response.suggestions().get(0).lineIndex());
        assertEquals(1, response.subjectTags().size());
        assertEquals(0, response.subjectTags().get(0).lineIndex());
    }

    @Test
    void extraction_dropsSubjectsThisInterviewDoesNotScore() {
        String raw = """
                {"suggestions":[],"subjectTags":[
                  {"lineIndex":0,"subjectCode":"CULTURE"},
                  {"lineIndex":1,"subjectCode":"UNCERTAINTY"},
                  {"lineIndex":2,"subjectCode":"' OR 1=1 --"}
                ]}""";
        List<RoomSubjectTag> tags =
                InterviewRoomAiService.validateExtraction(raw, SUBJECTS, 3).subjectTags();
        assertEquals(1, tags.size());
        assertEquals("CULTURE", tags.get(0).subjectCode());
    }

    /** A line is evidence for ONE subject or none — the first tag wins. */
    @Test
    void extraction_keepsOneSubjectTagPerLine() {
        String raw = """
                {"suggestions":[],"subjectTags":[
                  {"lineIndex":0,"subjectCode":"CULTURE"},
                  {"lineIndex":0,"subjectCode":"FAGLIGHED"}
                ]}""";
        List<RoomSubjectTag> tags =
                InterviewRoomAiService.validateExtraction(raw, SUBJECTS, 1).subjectTags();
        assertEquals(1, tags.size());
        assertEquals("CULTURE", tags.get(0).subjectCode());
    }

    /** No template subjects resolved ⇒ job 2 has no allowed codes at all. */
    @Test
    void extraction_dropsEveryTagWhenTheInterviewHasNoSubjects() {
        String raw = """
                {"suggestions":[],"subjectTags":[{"lineIndex":0,"subjectCode":"CULTURE"}]}""";
        assertTrue(InterviewRoomAiService.validateExtraction(raw, Set.of(), 1)
                .subjectTags().isEmpty());
    }

    @Test
    void extraction_rejectsTrailingScratchpad() {
        String raw = "{\"suggestions\":[{\"lineIndex\":0,\"field\":\"EARLIEST_START\","
                + "\"value\":\"2026-12-01\",\"evidence\":\"x\"}],\"subjectTags\":[]}"
                + " assistant to=final: done";
        RoomSuggestResponse response =
                InterviewRoomAiService.validateExtraction(raw, SUBJECTS, 1);
        assertTrue(response.suggestions().isEmpty(),
                "trailing model scratchpad fails the single-document parse");
        assertTrue(response.subjectTags().isEmpty());
    }

    // ---- Tidy (§9) --------------------------------------------------------

    /** "Writes nothing into a subject that has no lines — it will not fill a gap." */
    @Test
    void tidy_neverFillsAnEmptySubject() {
        String raw = """
                {"subjects":[
                  {"subjectCode":"CULTURE","prose":"Honest and warm, owns failures."},
                  {"subjectCode":"COMMERCIAL_DRIVE","prose":"Probably fine at sales."}
                ],"alignmentNotes":[]}""";
        RoomTidyResponse response = InterviewRoomAiService.validateTidy(
                raw, Set.of("CULTURE"), false);
        assertEquals(1, response.subjects().size());
        assertEquals("CULTURE", response.subjects().get(0).subjectCode());
    }

    @Test
    void tidy_dropsAlignmentNotesWhenTheFlagIsOff() {
        String raw = """
                {"subjects":[{"subjectCode":"CULTURE","prose":"x"}],
                 "alignmentNotes":["CULTURE evidence reads as FAGLIGHED"]}""";
        assertTrue(InterviewRoomAiService.validateTidy(raw, Set.of("CULTURE"), false)
                .alignmentNotes().isEmpty());
        assertEquals(1, InterviewRoomAiService.validateTidy(raw, Set.of("CULTURE"), true)
                .alignmentNotes().size());
    }

    // ---- Prep pack (§9) ---------------------------------------------------

    /** Questions, never conclusions — every surviving entry ends in "?". */
    @Test
    void prep_keepsOnlyQuestions() {
        String raw = """
                {"probes":[
                  {"subjectCode":"CULTURE","questions":[
                     "What would you run a faglig fredag on, given your Kafka work?",
                     "This candidate seems guarded and rehearsed."]},
                  {"subjectCode":"NOT_IN_TEMPLATE","questions":["Why?"]}
                ]}""";
        List<PrepSubject> probes = InterviewRoomAiService.validatePrep(
                raw, Set.of("CULTURE", "FAGLIGHED"));
        assertEquals(1, probes.size());
        assertEquals(List.of("What would you run a faglig fredag on, given your Kafka work?"),
                probes.get(0).questions());
    }
}
