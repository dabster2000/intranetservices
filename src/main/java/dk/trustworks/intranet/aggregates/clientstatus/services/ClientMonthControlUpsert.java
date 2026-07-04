package dk.trustworks.intranet.aggregates.clientstatus.services;

import dk.trustworks.intranet.aggregates.clientstatus.model.ClientMonthControlHistory.Action;

import java.util.Objects;

/**
 * Pure decision logic for a single client-month controlling upsert — extracted so the
 * approve/unapprove/note/no-op matrix is unit-testable without a database. Given the prior state and
 * the requested change, it decides which approval action (if any), which note action (if any), and
 * therefore whether anything changed at all and which action to record in the audit trail.
 *
 * <p>The transactional {@link ClientMonthControlService} applies the result to the persisted
 * aggregate; all rule decisions live here.</p>
 */
public final class ClientMonthControlUpsert {

    private ClientMonthControlUpsert() {}

    /**
     * @param approvalAction the approval transition to record (null = approval unchanged)
     * @param noteAction     {@link Action#NOTE_UPDATED} when the note changed, else null
     * @param newNote        the note to store when {@code noteAction} is set (null clears it)
     * @param noteChanged    whether the note field must be written
     */
    public record Decision(Action approvalAction, Action noteAction, String newNote, boolean noteChanged) {
        /** True when neither the approval nor the note changed — the caller should persist nothing. */
        public boolean isNoOp() {
            return approvalAction == null && noteAction == null;
        }

        /** The single action to record in history: the approval transition takes precedence over a note edit. */
        public Action historyAction() {
            return approvalAction != null ? approvalAction : noteAction;
        }
    }

    /**
     * Decide the effect of an upsert.
     *
     * @param wasApproved whether the cell already had an approval snapshot
     * @param currentNote the note currently stored (nullable)
     * @param approved    null = leave approval unchanged; true = approve/re-approve; false = unapprove
     * @param note        null = leave note unchanged; "" = clear; else set
     */
    public static Decision decide(boolean wasApproved, String currentNote,
                                  Boolean approved, String note) {
        Action approvalAction = null;
        if (approved != null) {
            if (approved) {
                approvalAction = wasApproved ? Action.REAPPROVED : Action.APPROVED;
            } else if (wasApproved) {
                approvalAction = Action.UNAPPROVED;
            }
            // approved=false on a never-approved cell is a no-op for the approval axis.
        }

        Action noteAction = null;
        String newNote = currentNote;
        boolean noteChanged = false;
        if (note != null) {
            // Blank (incl. whitespace-only) clears — a stored note must never be blank, or the
            // grid's hasNote flag and the PUT echo would disagree.
            String candidate = note.isBlank() ? null : note.trim();
            if (!Objects.equals(candidate, currentNote)) {
                newNote = candidate;
                noteChanged = true;
                noteAction = Action.NOTE_UPDATED;
            }
        }

        return new Decision(approvalAction, noteAction, newNote, noteChanged);
    }
}
