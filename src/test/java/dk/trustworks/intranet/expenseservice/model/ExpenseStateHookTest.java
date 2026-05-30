package dk.trustworks.intranet.expenseservice.model;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.quarkus.test.TestTransaction;
import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import org.junit.jupiter.api.Test;

import java.time.LocalDate;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

@QuarkusTest
class ExpenseStateHookTest {

    @Inject ObjectMapper objectMapper;

    private Expense base() {
        Expense e = new Expense();
        e.setUuid(UUID.randomUUID().toString());
        e.setUseruuid("test-user");
        e.setAmount(100.0);
        e.setAccount("3585");
        e.setAccountname("Frokost");
        e.setDescription("test");
        e.setExpensedate(LocalDate.now());
        e.setDatecreated(LocalDate.now());
        return e;
    }

    @Test @TestTransaction
    void prePersist_ignoresLegacyReviewState_forFreshCreated() {
        // Phase 3: the hook no longer consults review_state. A fresh CREATED row with a
        // legacy review_state set but no unified state derives SUBMITTED from status alone.
        Expense e = base();
        e.setStatus("CREATED");
        e.setReviewState("PENDING_HR"); // legacy column — must be ignored by the hook now
        e.persist();
        Expense.getEntityManager().flush();
        Expense.getEntityManager().clear();

        Expense round = Expense.findById(e.getUuid());
        assertEquals("SUBMITTED", round.getState());
        assertNull(round.getAttentionOwner());
        assertNull(round.getAttentionKind());
    }

    @Test @TestTransaction
    void preUpdate_refreshesState_whenStatusAdvances() {
        Expense e = base();
        e.setStatus("CREATED");
        e.persist();
        Expense.getEntityManager().flush();

        Expense managed = Expense.findById(e.getUuid());
        managed.setStatus("VERIFIED_BOOKED");
        managed.persist();
        Expense.getEntityManager().flush();
        Expense.getEntityManager().clear();

        Expense round = Expense.findById(e.getUuid());
        assertEquals("BOOKED", round.getState());
        assertNull(round.getAttentionOwner());
        assertNull(round.getAttentionKind());
    }

    @Test @TestTransaction
    void state_isSerializedToJson() throws Exception {
        Expense e = base();
        e.setStatus("VALIDATED");
        e.persist();
        Expense round = Expense.findById(e.getUuid());

        String json = objectMapper.writeValueAsString(round);
        assertTrue(json.contains("\"state\":\"APPROVED\""), json);
    }

    @Test @TestTransaction
    void prePersist_freshCreated_defaultsToSubmitted() {
        Expense e = base();
        e.setStatus("CREATED");
        e.persist();
        Expense round = Expense.findById(e.getUuid());
        assertEquals("SUBMITTED", round.getState());
        assertNull(round.getAttentionOwner());
    }

    @Test @TestTransaction
    void preUpdate_preservesDirectlyWrittenHeadState() {
        // Workflow writes NEEDS_ATTENTION directly while status stays CREATED.
        Expense e = base();
        e.setStatus("CREATED");
        e.persist();
        Expense.getEntityManager().flush();

        Expense managed = Expense.findById(e.getUuid());
        managed.setState("NEEDS_ATTENTION");
        managed.setAttentionOwner("EMPLOYEE");
        managed.setAttentionKind("AMOUNT_MISMATCH");
        managed.persist();
        Expense.getEntityManager().flush();
        Expense.getEntityManager().clear();

        Expense round = Expense.findById(e.getUuid());
        assertEquals("NEEDS_ATTENTION", round.getState(), "hook must not clobber a direct head write");
        assertEquals("EMPLOYEE", round.getAttentionOwner());
        assertEquals("AMOUNT_MISMATCH", round.getAttentionKind());
    }

    @Test @TestTransaction
    void preUpdate_tailStateDerivedFromStatus_ignoresStaleAttributes() {
        Expense e = base();
        e.setStatus("CREATED");
        e.setState("NEEDS_ATTENTION");
        e.setAttentionOwner("EMPLOYEE");
        e.persist();
        Expense.getEntityManager().flush();

        // Pipeline advances status into the tail; stale head attributes must clear.
        Expense managed = Expense.findById(e.getUuid());
        managed.setStatus("VERIFIED_BOOKED");
        managed.persist();
        Expense.getEntityManager().flush();
        Expense.getEntityManager().clear();

        Expense round = Expense.findById(e.getUuid());
        assertEquals("BOOKED", round.getState());
        assertNull(round.getAttentionOwner());
        assertNull(round.getAttentionKind());
    }

    @Test @TestTransaction
    void preUpdate_rejectTerminalIsPreserved() {
        // Accounting reject: status=DELETED + state=REJECTED written together.
        Expense e = base();
        e.setStatus("CREATED");
        e.setState("NEEDS_ATTENTION");
        e.setAttentionOwner("ACCOUNTING");
        e.persist();
        Expense.getEntityManager().flush();

        Expense managed = Expense.findById(e.getUuid());
        managed.setStatus("DELETED");
        managed.setState("REJECTED");
        managed.setAttentionOwner(null);
        managed.setAttentionKind(null);
        managed.persist();
        Expense.getEntityManager().flush();
        Expense.getEntityManager().clear();

        Expense round = Expense.findById(e.getUuid());
        assertEquals("REJECTED", round.getState(), "DELETED is authoritative; REJECTED must survive");
    }

    @Test @TestTransaction
    void aiOutcome_isSerializedWhenPopulated() throws Exception {
        Expense e = base();
        e.setStatus("CREATED");
        e.setAiOutcome("SOFT_FLAG");
        e.setAiConfidence(0.42);
        e.setSoftFlags("[\"AMOUNT_MISMATCH\"]");
        e.persist();
        Expense round = Expense.findById(e.getUuid());

        String json = objectMapper.writeValueAsString(round);
        assertTrue(json.contains("\"aiOutcome\":\"SOFT_FLAG\""), json);
        assertTrue(json.contains("\"aiConfidence\":0.42"), json);
        assertTrue(json.contains("\"softFlags\":[\"AMOUNT_MISMATCH\"]"), json);
    }
}
