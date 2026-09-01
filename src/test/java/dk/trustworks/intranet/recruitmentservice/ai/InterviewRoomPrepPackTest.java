package dk.trustworks.intranet.recruitmentservice.ai;

import dk.trustworks.intranet.recruitmentservice.dto.RoomTidyRequest.TidyLine;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The prep pack's two 2026-09-01 changes: it answers in Danish whatever
 * language the material is in, and it can be re-run mid-interview against the
 * interviewer's live notes.
 *
 * <p>Both are prompt/input contracts rather than model behaviour, so they are
 * testable without an OpenAI round-trip — which is the point: the room's AI
 * guarantees are enforced in code, never trusted to the model.</p>
 *
 * <p>DB-free by design: runs in the fast tier that gates deploys.</p>
 */
class InterviewRoomPrepPackTest {

    // ---- Danish, unconditionally ------------------------------------------

    @Test
    @DisplayName("the prep prompt instructs Danish rather than permitting it")
    void prepSystem_demandsDanish() {
        for (boolean withNotes : new boolean[]{false, true}) {
            String system = InterviewRoomPrompts.prepSystem(withNotes);
            assertTrue(system.contains("write every question in Danish"),
                    "the output language must be an instruction, not a hint");
            assertTrue(system.contains("only acceptable output language"),
                    "an English CV must not license an English pack");
            // The wording it replaced let the output language follow the input,
            // which is exactly how a pack came back half Danish, half English.
            assertFalse(system.contains("Danish is fine where the material is Danish"),
                    "the permissive wording is what produced mixed-language packs");
        }
    }

    @Test
    @DisplayName("questions-only survives both prompt shapes")
    void prepSystem_keepsTheQuestionsOnlyContract() {
        for (boolean withNotes : new boolean[]{false, true}) {
            String system = InterviewRoomPrompts.prepSystem(withNotes);
            assertTrue(system.contains("QUESTIONS ONLY"), "withNotes=" + withNotes);
            assertTrue(system.contains("must end with a question mark"), "withNotes=" + withNotes);
            assertTrue(system.contains("Never a conclusion"), "withNotes=" + withNotes);
        }
    }

    @Test
    @DisplayName("live notes are described to the model only when they are sent")
    void prepSystem_onlyExplainsNotesWhenThereAreNotes() {
        assertFalse(InterviewRoomPrompts.prepSystem(false).contains("ALREADY UNDERWAY"),
                "the pre-interview pack has no notes to reason about");
        String withNotes = InterviewRoomPrompts.prepSystem(true);
        assertTrue(withNotes.contains("ALREADY UNDERWAY"));
        assertTrue(withNotes.contains("[CODE, verbatim]"),
                "the model must be told how a note line is shaped");
        assertTrue(withNotes.contains("never conclude from one"),
                "notes are the interviewer's shorthand, not a record to conclude from");
    }

    /**
     * The AI Act event store stamps every room event with this constant, so a
     * prompt change that keeps the old version is a change no audit can see.
     */
    @Test
    @DisplayName("the prompt version moved off room-v1 with the prompt")
    void promptVersion_wasBumped() {
        assertNotEquals("room-v1", InterviewRoomPrompts.PROMPT_VERSION);
    }

    // ---- Live notes: one shape, and a budget -------------------------------

    @Test
    @DisplayName("a note line reaches the prep pack in the same shape Tidy sends")
    void boundedPrepNotes_rendersTheTidyLineShape() {
        List<String> rendered = InterviewRoomAiService.boundedPrepNotes(List.of(
                new TidyLine("1", "  bygget CI/CD fra bunden  ", "FAGLIGHED", false),
                new TidyLine("2", "\"jeg savnede at eje noget\"", "WHY_CONSULTING", true),
                new TidyLine("3", "skal hente barn 16.30", null, false)));
        assertEquals(List.of(
                        "[FAGLIGHED] bygget CI/CD fra bunden",
                        "[WHY_CONSULTING, verbatim] \"jeg savnede at eje noget\"",
                        "[loose] skal hente barn 16.30"),
                rendered);
    }

    @Test
    @DisplayName("no notes is the ordinary pre-interview pack")
    void boundedPrepNotes_emptyForNullAndEmpty() {
        assertTrue(InterviewRoomAiService.boundedPrepNotes(null).isEmpty());
        assertTrue(InterviewRoomAiService.boundedPrepNotes(List.of()).isEmpty());
    }

    @Test
    @DisplayName("blank and null-texted lines carry nothing and are left out")
    void boundedPrepNotes_dropsUnusableLines() {
        assertTrue(InterviewRoomAiService.boundedPrepNotes(List.of(
                        new TidyLine("1", "   ", "CULTURE", false),
                        new TidyLine("2", null, "CULTURE", false)))
                .isEmpty());
    }

    /**
     * The notepad grows all sitting and the CV is bounded for the same reason.
     * What survives is the TAIL: a follow-up question is asked about what was
     * just said, not about the first thing said an hour ago.
     */
    @Test
    @DisplayName("over the line budget the newest lines are the ones that survive")
    void boundedPrepNotes_keepsTheNewestLines() {
        List<TidyLine> notes = new ArrayList<>();
        for (int i = 0; i < InterviewRoomAiService.MAX_PREP_NOTE_LINES + 40; i++) {
            notes.add(new TidyLine(String.valueOf(i), "linje " + i, "CULTURE", false));
        }
        List<String> kept = InterviewRoomAiService.boundedPrepNotes(notes);
        assertEquals(InterviewRoomAiService.MAX_PREP_NOTE_LINES, kept.size());
        assertEquals("[CULTURE] linje 40", kept.get(0));
        assertEquals("[CULTURE] linje " + (notes.size() - 1), kept.get(kept.size() - 1));
    }

    @Test
    @DisplayName("a few very long lines cannot crowd out the CV either")
    void boundedPrepNotes_keepsWithinTheCharacterBudget() {
        List<TidyLine> notes = new ArrayList<>();
        for (int i = 0; i < 30; i++) {
            notes.add(new TidyLine(String.valueOf(i), "x".repeat(500), "CULTURE", false));
        }
        List<String> kept = InterviewRoomAiService.boundedPrepNotes(notes);
        assertTrue(kept.stream().mapToInt(String::length).sum()
                        <= InterviewRoomAiService.MAX_PREP_NOTE_CHARS,
                "the note budget must hold regardless of how few lines it takes to fill");
        assertFalse(kept.isEmpty());
        // Still the tail — the last line written is the last line sent.
        assertEquals("[CULTURE] " + "x".repeat(500), kept.get(kept.size() - 1));
    }
}
