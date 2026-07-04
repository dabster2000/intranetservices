package dk.trustworks.intranet.aggregates.clientstatus.services;

import dk.trustworks.intranet.aggregates.clientstatus.model.ClientMonthControlHistory.Action;
import dk.trustworks.intranet.aggregates.clientstatus.services.ClientMonthControlUpsert.Decision;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/** Pure unit tests (no Quarkus, no DB) for the upsert approve/unapprove/note/no-op decision matrix. */
class ClientMonthControlUpsertTest {

    @Test
    void approve_freshCell_isApproved() {
        Decision d = ClientMonthControlUpsert.decide(false, null, true, null);
        assertEquals(Action.APPROVED, d.approvalAction());
        assertNull(d.noteAction());
        assertFalse(d.isNoOp());
        assertEquals(Action.APPROVED, d.historyAction());
    }

    @Test
    void approve_alreadyApproved_isReapproved() {
        Decision d = ClientMonthControlUpsert.decide(true, null, true, null);
        assertEquals(Action.REAPPROVED, d.approvalAction());
        assertEquals(Action.REAPPROVED, d.historyAction());
    }

    @Test
    void unapprove_approvedCell_isUnapproved() {
        Decision d = ClientMonthControlUpsert.decide(true, "keep me", false, null);
        assertEquals(Action.UNAPPROVED, d.approvalAction());
        assertFalse(d.noteChanged(), "unapprove leaves the note untouched");
    }

    @Test
    void unapprove_neverApproved_isNoOp() {
        Decision d = ClientMonthControlUpsert.decide(false, null, false, null);
        assertTrue(d.isNoOp());
        assertNull(d.historyAction());
    }

    @Test
    void noteOnly_setsNote_actionIsNoteUpdated() {
        Decision d = ClientMonthControlUpsert.decide(false, null, null, "new note");
        assertNull(d.approvalAction());
        assertEquals(Action.NOTE_UPDATED, d.noteAction());
        assertTrue(d.noteChanged());
        assertEquals("new note", d.newNote());
        assertEquals(Action.NOTE_UPDATED, d.historyAction());
    }

    @Test
    void noteOnly_clearWithEmptyString_setsNull() {
        Decision d = ClientMonthControlUpsert.decide(false, "old", null, "");
        assertEquals(Action.NOTE_UPDATED, d.noteAction());
        assertTrue(d.noteChanged());
        assertNull(d.newNote(), "empty string clears the note");
    }

    @Test
    void noteOnly_sameValue_isNoOp() {
        Decision d = ClientMonthControlUpsert.decide(false, "same", null, "same");
        assertTrue(d.isNoOp(), "setting the note to its current value changes nothing");
        assertFalse(d.noteChanged());
    }

    @Test
    void noteOnly_clearAlreadyEmpty_isNoOp() {
        Decision d = ClientMonthControlUpsert.decide(false, null, null, "");
        assertTrue(d.isNoOp());
    }

    @Test
    void noteOnly_whitespaceOnly_clearsLikeEmpty() {
        // A stored blank note would make the grid's hasNote flag disagree with the PUT echo.
        Decision d = ClientMonthControlUpsert.decide(false, "old", null, "   ");
        assertEquals(Action.NOTE_UPDATED, d.noteAction());
        assertNull(d.newNote(), "whitespace-only input clears the note");
    }

    @Test
    void noteOnly_whitespaceOnClearedNote_isNoOp() {
        Decision d = ClientMonthControlUpsert.decide(false, null, null, "   ");
        assertTrue(d.isNoOp());
    }

    @Test
    void noteOnly_surroundingWhitespaceIsTrimmed() {
        Decision d = ClientMonthControlUpsert.decide(false, null, null, "  awaiting credit note  ");
        assertTrue(d.noteChanged());
        assertEquals("awaiting credit note", d.newNote());
    }

    @Test
    void noteOnly_trimmedValueEqualToCurrent_isNoOp() {
        Decision d = ClientMonthControlUpsert.decide(false, "same", null, "  same  ");
        assertTrue(d.isNoOp(), "input differing only by surrounding whitespace changes nothing");
    }

    @Test
    void approveAndNote_bothApplied_historyPrefersApproval() {
        Decision d = ClientMonthControlUpsert.decide(false, null, true, "with note");
        assertEquals(Action.APPROVED, d.approvalAction());
        assertEquals(Action.NOTE_UPDATED, d.noteAction());
        assertTrue(d.noteChanged());
        assertEquals("with note", d.newNote());
        assertEquals(Action.APPROVED, d.historyAction(), "approval transition wins as the recorded action");
    }

    @Test
    void unapproveAndNote_bothApplied() {
        Decision d = ClientMonthControlUpsert.decide(true, "old", false, "updated");
        assertEquals(Action.UNAPPROVED, d.approvalAction());
        assertEquals(Action.NOTE_UPDATED, d.noteAction());
        assertEquals("updated", d.newNote());
        assertEquals(Action.UNAPPROVED, d.historyAction());
    }
}
