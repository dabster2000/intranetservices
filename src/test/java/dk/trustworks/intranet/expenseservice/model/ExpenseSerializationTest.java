package dk.trustworks.intranet.expenseservice.model;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import org.junit.jupiter.api.Test;

import java.time.LocalDate;
import java.time.LocalDateTime;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Pure Jackson serialization test for the {@link Expense} entity.
 *
 * <p>Guards the wire-format contract used by {@code GET /expenses/{uuid}},
 * {@code GET /expenses/user/{useruuid}}, and {@code GET /expenses/search/period}:
 * </p>
 * <ul>
 *   <li>All new review-workflow fields (reviewState, aiRuleId, hr*, etc.) are emitted.</li>
 *   <li>{@code aiRuleIdsJson} (raw column) is {@code @JsonIgnore} and NOT on the wire.</li>
 *   <li>{@code aiRuleIds} is a typed string array, parsed from the raw JSON column.</li>
 *   <li>Null / blank / malformed JSON degrades to an empty array (no API crash).</li>
 * </ul>
 *
 * <p>Plain JUnit 5 — no {@code @QuarkusTest} so it runs in pure surefire without Docker/MariaDB.</p>
 */
class ExpenseSerializationTest {

    private static ObjectMapper mapper() {
        return new ObjectMapper().registerModule(new JavaTimeModule());
    }

    private static Expense baseExpense() {
        Expense e = new Expense();
        e.setUuid("expense-uuid-1");
        e.setUseruuid("user-uuid-1");
        e.setAmount(123.45);
        e.setAccount("3520");
        e.setExpensedate(LocalDate.of(2026, 5, 17));
        e.setDatecreated(LocalDate.of(2026, 5, 17));
        e.setStatus("PENDING");
        return e;
    }

    @Test
    void allReviewFieldsSerialize() throws Exception {
        Expense e = baseExpense();
        e.setReviewState("NEEDS_JUSTIFICATION");
        e.setAiRuleId("R_MEAL_COST_PER_PERSON");
        e.setAiRuleIdsJson("[\"R_MEAL_COST_PER_PERSON\",\"R_RECEIPT_QUALITY\"]");
        e.setEmployeeJustification("Client lunch");
        e.setHrComment("OK");
        e.setHrDecision("APPROVED");
        e.setHrDecisionBy("hr-user-uuid");
        e.setHrDecisionAt(LocalDateTime.of(2026, 5, 17, 12, 0, 0));
        e.setAiValidationCount(2);
        e.setVersion(7);

        String json = mapper().writeValueAsString(e);
        JsonNode node = mapper().readTree(json);

        assertEquals("NEEDS_JUSTIFICATION", node.get("reviewState").asText());
        assertEquals("R_MEAL_COST_PER_PERSON", node.get("aiRuleId").asText());

        assertTrue(node.get("aiRuleIds").isArray(), "aiRuleIds must serialize as an array");
        assertEquals(2, node.get("aiRuleIds").size());
        assertEquals("R_MEAL_COST_PER_PERSON", node.get("aiRuleIds").get(0).asText());
        assertEquals("R_RECEIPT_QUALITY", node.get("aiRuleIds").get(1).asText());

        assertEquals("Client lunch", node.get("employeeJustification").asText());
        assertEquals("OK", node.get("hrComment").asText());
        assertEquals("APPROVED", node.get("hrDecision").asText());
        assertEquals("hr-user-uuid", node.get("hrDecisionBy").asText());
        assertTrue(node.has("hrDecisionAt"), "hrDecisionAt must be present on the wire");
        assertEquals(2, node.get("aiValidationCount").asInt());
        assertEquals(7, node.get("version").asInt());

        // Raw JSON column must NOT appear on the wire (@JsonIgnore).
        assertFalse(node.has("aiRuleIdsJson"),
                "aiRuleIdsJson is @JsonIgnore and must not be serialized");
    }

    @Test
    void emptyAiRuleIdsJsonSerializesAsEmptyArray() throws Exception {
        Expense e = baseExpense();
        e.setAiRuleIdsJson(null);

        JsonNode node = mapper().readTree(mapper().writeValueAsString(e));

        assertTrue(node.get("aiRuleIds").isArray(), "aiRuleIds must serialize as an array");
        assertEquals(0, node.get("aiRuleIds").size(), "null aiRuleIdsJson -> empty array");
        assertFalse(node.has("aiRuleIdsJson"));
    }

    @Test
    void malformedAiRuleIdsJsonSerializesAsEmptyArray() throws Exception {
        Expense e = baseExpense();
        e.setAiRuleIdsJson("not valid json {");

        JsonNode node = mapper().readTree(mapper().writeValueAsString(e));

        assertTrue(node.get("aiRuleIds").isArray(),
                "malformed JSON must degrade to an empty array, not crash");
        assertEquals(0, node.get("aiRuleIds").size());
        assertFalse(node.has("aiRuleIdsJson"));
    }
}
