package dk.trustworks.intranet.expenseservice.model;

import org.junit.jupiter.api.Test;

import static dk.trustworks.intranet.expenseservice.model.ExpenseStateDeriver.*;
import static org.junit.jupiter.api.Assertions.*;

/** Pure unit tests — no Quarkus, no DB. Mirrors the V357 backfill CASE logic. */
class ExpenseStateDeriverTest {

    @Test void verifiedBooked_isBooked_noOwner() {
        DerivedState d = derive("VERIFIED_BOOKED", "PENDING_HR", Boolean.FALSE, null);
        assertEquals(BOOKED, d.state());
        assertNull(d.owner());
        assertNull(d.kind());
    }

    @Test void fictionQueue_postedTakesPrecedenceOverStalePendingHr() {
        // The 169-item fiction queue: already in e-conomic journal, stale PENDING_HR flag.
        DerivedState d = derive("VERIFIED_UNBOOKED", "PENDING_HR", Boolean.TRUE, null);
        assertEquals(POSTED, d.state());
        assertNull(d.owner(), "stale review flag must be dropped on already-posted rows");
    }

    @Test void uploadInProgress_isPosting() {
        assertEquals(POSTING, derive("PROCESSING", null, null, null).state());
        assertEquals(POSTING, derive("UPLOADED", null, null, null).state());
        assertEquals(POSTING, derive("VOUCHER_CREATED", null, null, null).state());
    }

    @Test void technicalFailure_isNeedsAttention_accounting_technical() {
        for (String s : new String[]{"UP_FAILED", "NO_FILE", "NO_USER"}) {
            DerivedState d = derive(s, null, null, null);
            assertEquals(NEEDS_ATTENTION, d.state(), s);
            assertEquals(OWNER_ACCOUNTING, d.owner(), s);
            assertEquals(KIND_TECHNICAL, d.kind(), s);
        }
    }

    @Test void validated_isApproved() {
        assertEquals(APPROVED, derive("VALIDATED", null, Boolean.TRUE, null).state());
    }

    @Test void createdAndAiPending_isSubmitted() {
        DerivedState d = derive("CREATED", null, null, null);
        assertEquals(SUBMITTED, d.state());
        assertNull(d.owner());
    }

    @Test void needsFix_isEmployeeReceipt() {
        DerivedState d = derive("CREATED", "NEEDS_FIX", Boolean.FALSE, null);
        assertEquals(NEEDS_ATTENTION, d.state());
        assertEquals(OWNER_EMPLOYEE, d.owner());
        assertEquals(KIND_RECEIPT, d.kind());
    }

    @Test void needsJustificationOrSentBack_isEmployeeJustification() {
        assertEquals(KIND_JUSTIFICATION, derive("CREATED", "NEEDS_JUSTIFICATION", Boolean.FALSE, null).kind());
        DerivedState back = derive("CREATED", "HR_SENT_BACK", Boolean.FALSE, "SENT_BACK");
        assertEquals(NEEDS_ATTENTION, back.state());
        assertEquals(OWNER_EMPLOYEE, back.owner());
        assertEquals(KIND_JUSTIFICATION, back.kind());
    }

    @Test void pendingHr_isAccountingPolicy() {
        DerivedState d = derive("CREATED", "PENDING_HR", Boolean.TRUE, null);
        assertEquals(NEEDS_ATTENTION, d.state());
        assertEquals(OWNER_ACCOUNTING, d.owner());
        assertEquals(KIND_POLICY, d.kind());
    }

    @Test void legacyAiRejectedNeverRouted_isAccountingPolicy() {
        DerivedState d = derive("CREATED", null, Boolean.FALSE, null);
        assertEquals(NEEDS_ATTENTION, d.state());
        assertEquals(OWNER_ACCOUNTING, d.owner());
        assertEquals(KIND_POLICY, d.kind());
    }

    @Test void hrRejected_isRejected_notDeleted() {
        assertEquals(REJECTED, derive("DELETED", null, null, "REJECTED").state());
    }

    @Test void employeeDeleted_isDeleted() {
        DerivedState d = derive("DELETED", null, null, null);
        assertEquals(DELETED, d.state());
        assertNull(d.owner());
    }

    @Test void nullStatus_isSubmitted() {
        assertEquals(SUBMITTED, derive(null, null, null, null).state());
    }

    @Test void unknownLegacyStatus_isSubmitted() {
        assertEquals(SUBMITTED, derive("PDF", null, null, null).state());
    }

    // ---- Phase 1: tail-only derivation + boundary predicate ----

    @Test void deriveFromStatus_pipelineMapsToTail() {
        assertEquals(BOOKED, deriveFromStatus("VERIFIED_BOOKED").state());
        assertEquals(POSTED, deriveFromStatus("VERIFIED_UNBOOKED").state());
        assertEquals(POSTING, deriveFromStatus("UPLOADED").state());
        assertEquals(POSTING, deriveFromStatus("VOUCHER_CREATED").state());
        assertEquals(POSTING, deriveFromStatus("PROCESSING").state());
        assertEquals(APPROVED, deriveFromStatus("VALIDATED").state());
    }

    @Test void deriveFromStatus_technicalFailureIsAccountingTechnical() {
        for (String s : new String[]{"UP_FAILED", "NO_FILE", "NO_USER"}) {
            DerivedState d = deriveFromStatus(s);
            assertEquals(NEEDS_ATTENTION, d.state(), s);
            assertEquals(OWNER_ACCOUNTING, d.owner(), s);
            assertEquals(KIND_TECHNICAL, d.kind(), s);
        }
    }

    @Test void deriveFromStatus_deletedIsDeleted_noHrSplit() {
        // Tail DELETED is the e-conomic/employee delete; accounting REJECT is an
        // authoritative head write, never routed through this method.
        assertEquals(DELETED, deriveFromStatus("DELETED").state());
    }

    @Test void isStatusDerivedState_trueForPipelineAndValidated_falseForHeadTerminal() {
        for (String s : new String[]{"PROCESSING","UPLOADED","VOUCHER_CREATED",
                "VERIFIED_UNBOOKED","VERIFIED_BOOKED","UP_FAILED","NO_FILE","NO_USER","VALIDATED"}) {
            assertTrue(isStatusDerivedState(s), s);
        }
        for (String s : new String[]{"CREATED","DELETED"}) {
            assertFalse(isStatusDerivedState(s), s);
        }
        assertFalse(isStatusDerivedState(null));
        assertFalse(isStatusDerivedState("PDF"));
    }

    @Test void amountMismatchKindMapsToNeedsFixLegacyReviewState() {
        assertEquals("NEEDS_FIX", mapAttentionKindToLegacyReviewState(KIND_AMOUNT_MISMATCH));
        assertEquals("NEEDS_FIX", mapAttentionKindToLegacyReviewState(KIND_RECEIPT));
        assertEquals("NEEDS_JUSTIFICATION", mapAttentionKindToLegacyReviewState(KIND_JUSTIFICATION));
        assertEquals("PENDING_HR", mapAttentionKindToLegacyReviewState(KIND_POLICY));
        assertNull(mapAttentionKindToLegacyReviewState(KIND_TECHNICAL));
    }
}
