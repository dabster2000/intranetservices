package dk.trustworks.intranet.expenseservice.model;

import org.junit.jupiter.api.Test;

import java.time.LocalDateTime;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Pure unit tests — no Quarkus, no DB. {@code syncDerivedState()} is invoked directly
 * (same package), exactly as JPA would on flush.
 *
 * <p>Covers the P0 fixes:
 * <ul>
 *   <li>the queue-age anchor {@code attention_since}: set on entry into NEEDS_ATTENTION,
 *       preserved while in it, cleared on exit;</li>
 *   <li>the prod approve-loop regression: a decision writing state=APPROVED over a
 *       technical status is reverted by the hook — and must NOT reset the age anchor;</li>
 *   <li>the CLOSED_MANUAL terminal derivation.</li>
 * </ul>
 */
class ExpenseAttentionSinceHookTest {

    private Expense technicalRow() {
        Expense e = new Expense();
        e.setUseruuid("u");
        e.setAmount(100.0);
        e.setStatus("UP_FAILED");
        e.syncDerivedState(); // enter the inbox
        return e;
    }

    @Test
    void enteringNeedsAttention_setsAnchor() {
        Expense e = technicalRow();
        assertEquals("NEEDS_ATTENTION", e.getState());
        assertEquals("ACCOUNTING", e.getAttentionOwner());
        assertEquals("TECHNICAL", e.getAttentionKind());
        assertNotNull(e.getAttentionSince(), "entering the inbox must stamp the age anchor");
    }

    @Test
    void stayingInNeedsAttention_preservesAnchor() {
        Expense e = technicalRow();
        LocalDateTime first = e.getAttentionSince();
        e.setDatemodified(java.time.LocalDate.now()); // any later write
        e.syncDerivedState();
        assertSame(first, e.getAttentionSince(), "anchor must survive later writes");
    }

    @Test
    void approveLoopRegression_hookRevertsDecisionButKeepsAnchor() {
        // The prod bug: approve() wrote state=APPROVED on an UP_FAILED row; the hook
        // re-derived NEEDS_ATTENTION in the same transaction, and datemodified-based age
        // read "0d". The revert is by design (the pipeline owns technical statuses) —
        // but the age anchor must NOT reset.
        Expense e = technicalRow();
        LocalDateTime entered = e.getAttentionSince();

        e.setState("APPROVED");        // what the decision service writes
        e.setAttentionOwner(null);
        e.setAttentionKind(null);
        e.syncDerivedState();          // flush

        assertEquals("NEEDS_ATTENTION", e.getState(), "technical status owns the state");
        assertEquals("TECHNICAL", e.getAttentionKind());
        assertSame(entered, e.getAttentionSince(), "a reverted decision must not zero the queue age");
    }

    @Test
    void leavingNeedsAttention_clearsAnchor() {
        Expense e = technicalRow();
        e.setStatus("VALIDATED"); // requeue: fresh upload
        e.syncDerivedState();
        assertEquals("APPROVED", e.getState());
        assertNull(e.getAttentionSince(), "anchor clears when the row leaves the inbox");
    }

    @Test
    void closedManual_isTerminalAndClearsAttention() {
        Expense e = technicalRow();
        e.setStatus("CLOSED_MANUAL");
        e.syncDerivedState();
        assertEquals("CLOSED_MANUAL", e.getState());
        assertNull(e.getAttentionOwner());
        assertNull(e.getAttentionKind());
        assertNull(e.getAttentionSince());
    }

    @Test
    void headStates_neverCarryAnchor() {
        Expense e = new Expense();
        e.setStatus("CREATED");
        e.syncDerivedState();
        assertEquals("SUBMITTED", e.getState());
        assertNull(e.getAttentionSince());
    }
}
