package dk.trustworks.intranet.expenseservice.model;

import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertNull;

/**
 * SECURITY (H-1): the server-managed workflow/AI fields must NOT be settable from client JSON.
 * They serialize OUT (READ_ONLY) but must be ignored on deserialize, so a client cannot inject
 * a fake state / AI verdict via POST /expenses.
 */
class ExpenseInputBindingTest {

    private static final ObjectMapper M = new ObjectMapper()
            .configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false);

    @Test
    void clientCannotSetWorkflowOrAiFields() throws Exception {
        String malicious = "{"
            + "\"amount\":\"100\","
            + "\"state\":\"APPROVED\","
            + "\"attentionOwner\":\"ACCOUNTING\","
            + "\"attentionKind\":\"POLICY\","
            + "\"aiOutcome\":\"APPROVE\","
            + "\"aiConfidence\":1.0"
            + "}";
        Expense e = M.readValue(malicious, Expense.class);
        assertNull(e.getState(),          "state must be ignored on deserialize (READ_ONLY)");
        assertNull(e.getAttentionOwner(), "attentionOwner must be ignored on deserialize");
        assertNull(e.getAttentionKind(),  "attentionKind must be ignored on deserialize");
        assertNull(e.getAiOutcome(),      "aiOutcome must be ignored on deserialize");
        assertNull(e.getAiConfidence(),   "aiConfidence must be ignored on deserialize");
    }
}
