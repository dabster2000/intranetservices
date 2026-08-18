package dk.trustworks.intranet.expenseservice.services;

import dk.trustworks.intranet.expenseservice.model.Expense;
import jakarta.ws.rs.BadRequestException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.LocalDateTime;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;

/**
 * Pure unit tests for the entity-level P0 pipeline actions (no Quarkus, no DB —
 * the uuid entry points add only lookup + transaction).
 */
@ExtendWith(MockitoExtension.class)
class ExpensePipelineActionServiceTest {

    @Mock ExpenseDecisionLogService logs;

    ExpensePipelineActionService service;

    @BeforeEach
    void setUp() {
        service = new ExpensePipelineActionService();
        service.logs = logs;
    }

    private Expense technical(String status, int voucher) {
        Expense e = new Expense();
        e.setUseruuid("u");
        e.setAmount(100.0);
        e.setAccount("3589");
        e.setStatus(status);
        e.setErrorMessage("HTTP 400: Account(s) is not found or barred");
        e.setRetryCount(3);
        e.setLastRetryAt(LocalDateTime.now().minusHours(1));
        e.setVouchernumber(voucher);
        // What the entity hook would derive for a technical status (hook is package-private
        // in the model package; this test asserts service logic, not the hook).
        e.setState("NEEDS_ATTENTION");
        e.setAttentionOwner("ACCOUNTING");
        e.setAttentionKind("TECHNICAL");
        return e;
    }

    // ---- requeue -----------------------------------------------------------------

    @Test
    void requeue_voucherless_goesBackToValidatedWithCountersCleared() {
        Expense e = technical("UP_FAILED", 0);
        service.applyRequeue(e, "actor", null, null, null);

        assertEquals("VALIDATED", e.getStatus(), "fresh upload attempt");
        assertNull(e.getErrorMessage());
        assertEquals(0, e.getRetryCount());
        assertNull(e.getLastRetryAt());
        verify(logs).recordPipelineRequeue(eq(e), eq("actor"), eq("VALIDATED"), any());
    }

    @Test
    void requeue_withVoucher_staysUpFailedSoRetryJobFinishesTheFileUpload() {
        // Re-posting a voucher-bearing row as VALIDATED would create a duplicate voucher;
        // the retry job (status=UP_FAILED, vouchernumber>0) finishes the file upload instead.
        Expense e = technical("UP_FAILED", 4711);
        service.applyRequeue(e, "actor", null, null, null);

        assertEquals("UP_FAILED", e.getStatus());
        assertEquals(0, e.getRetryCount(), "counters cleared so the retry job picks it up");
        assertNull(e.getLastRetryAt());
        verify(logs).recordPipelineRequeue(eq(e), eq("actor"), eq("UP_FAILED"), any());
    }

    @Test
    void requeue_withAccountFix_appliesAccountBeforeRequeue() {
        Expense e = technical("UP_FAILED", 0);
        service.applyRequeue(e, "actor", "2800", "Kontorudlæg", "dead account fixed");

        assertEquals("2800", e.getAccount());
        assertEquals("Kontorudlæg", e.getAccountname());
        assertEquals("VALIDATED", e.getStatus());
    }

    // ---- close -------------------------------------------------------------------

    @Test
    void close_setsManualTerminalAndAppendsNote() {
        Expense e = technical("UP_FAILED", 4711);
        service.applyClose(e, "actor", "BOOKED_MANUALLY", "j24-15062", "booked by accountant");

        assertEquals("CLOSED_MANUAL", e.getStatus());
        assertNotNull(e.getAccountantNotes());
        assertTrue(e.getAccountantNotes().contains("BOOKED_MANUALLY"));
        assertTrue(e.getAccountantNotes().contains("j24-15062"));
        verify(logs).recordPipelineClosed(eq(e), eq("actor"), eq("BOOKED_MANUALLY"), any());
    }

    @Test
    void close_preservesExistingAccountantNotes() {
        Expense e = technical("NO_USER", 0);
        e.setAccountantNotes("existing note");
        service.applyClose(e, "actor", "WRITTEN_OFF", null, "user never mapped");

        assertTrue(e.getAccountantNotes().startsWith("existing note\n"));
        assertTrue(e.getAccountantNotes().contains("WRITTEN_OFF"));
    }

    @Test
    void close_rejectsUnknownResolution() {
        Expense e = technical("UP_FAILED", 0);
        assertThrows(BadRequestException.class,
                () -> service.applyClose(e, "actor", "SHRUG", null, "reason"));
        assertEquals("UP_FAILED", e.getStatus(), "row untouched on refusal");
    }

    @Test
    void close_requiresReason() {
        Expense e = technical("UP_FAILED", 0);
        assertThrows(BadRequestException.class,
                () -> service.applyClose(e, "actor", "WRITTEN_OFF", null, "  "));
    }
}
