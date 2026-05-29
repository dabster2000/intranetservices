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
    void prePersist_derivesState_forPendingHr() {
        Expense e = base();
        e.setStatus("CREATED");
        e.setReviewState("PENDING_HR");
        e.persist();
        Expense.getEntityManager().flush();
        Expense.getEntityManager().clear();

        Expense round = Expense.findById(e.getUuid());
        assertEquals("NEEDS_ATTENTION", round.getState());
        assertEquals("ACCOUNTING", round.getAttentionOwner());
        assertEquals("POLICY", round.getAttentionKind());
    }

    @Test @TestTransaction
    void preUpdate_refreshesState_whenStatusAdvances() {
        Expense e = base();
        e.setStatus("CREATED");
        e.setReviewState("NEEDS_FIX");
        e.persist();
        Expense.getEntityManager().flush();

        Expense managed = Expense.findById(e.getUuid());
        managed.setStatus("VERIFIED_BOOKED");
        managed.setReviewState(null);
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
}
