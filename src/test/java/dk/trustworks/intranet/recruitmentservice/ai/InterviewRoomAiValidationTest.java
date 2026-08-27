package dk.trustworks.intranet.recruitmentservice.ai;

import dk.trustworks.intranet.recruitmentservice.dto.RoomPrepResponse.PrepSubject;
import dk.trustworks.intranet.recruitmentservice.dto.RoomSuggestResponse.RoomFactSuggestion;
import dk.trustworks.intranet.recruitmentservice.dto.RoomTidyResponse;
import org.junit.jupiter.api.Test;

import java.time.LocalDate;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The room's AI output validation (room spec 2026-08-26 §9) — the model
 * is untrusted, and these are the guards that make the oversight contract
 * real: vocabulary-keyed extraction with mandatory evidence, the Tidy gap
 * rule ("writes nothing into a subject that has no lines"), and the prep
 * pack's questions-only contract.
 */
class InterviewRoomAiValidationTest {

    private static final LocalDate TODAY = LocalDate.of(2026, 8, 26);

    // ---- Extraction (§5.4) ------------------------------------------------

    @Test
    void extraction_dropsFieldsOutsideTheVocabulary() {
        String raw = """
                {"suggestions":[
                  {"field":"NOTICE_PERIOD","value":"3 mdr","evidence":"3 mdr opsigelse"},
                  {"field":"PREGNANCY","value":"...","evidence":"..."},
                  {"field":"FAVOURITE_COLOUR","value":"blue","evidence":"likes blue"}
                ]}""";
        List<RoomFactSuggestion> suggestions =
                InterviewRoomAiService.validateSuggestions(raw, TODAY);
        assertEquals(1, suggestions.size());
        assertEquals("NOTICE_PERIOD", suggestions.get(0).field());
    }

    @Test
    void extraction_requiresEvidence() {
        String raw = """
                {"suggestions":[
                  {"field":"NOTICE_PERIOD","value":"3 mdr","evidence":""},
                  {"field":"EARLIEST_START","value":"2026-12-01"}
                ]}""";
        assertTrue(InterviewRoomAiService.validateSuggestions(raw, TODAY).isEmpty(),
                "a proposal without its source quote is dropped (spec §5.4)");
    }

    /** The spec §5.4 example: a start date the notice period cannot reach. */
    @Test
    void extraction_flagsArithmeticallyUnreachableStarts() {
        String raw = """
                {"suggestions":[
                  {"field":"NOTICE_PERIOD","value":"3 mdr","evidence":"3 mdr opsigelse"},
                  {"field":"PREFERRED_START","value":"2026-10-01","evidence":"vil gerne starte 1. okt"}
                ]}""";
        List<RoomFactSuggestion> suggestions =
                InterviewRoomAiService.validateSuggestions(raw, TODAY);
        assertEquals(2, suggestions.size());
        RoomFactSuggestion start = suggestions.get(1);
        assertNotNull(start.flag(), "1 Oct is not reachable with 3 months' notice from 26 Aug");
        assertTrue(start.flag().contains("2026-12-01"), start.flag());
        assertNull(suggestions.get(0).flag());
    }

    @Test
    void extraction_rejectsTrailingScratchpad() {
        String raw = "{\"suggestions\":[{\"field\":\"NOTICE_PERIOD\",\"value\":\"3 mdr\","
                + "\"evidence\":\"x\"}]} assistant to=final: done";
        assertTrue(InterviewRoomAiService.validateSuggestions(raw, TODAY).isEmpty(),
                "trailing model scratchpad fails the single-document parse");
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
