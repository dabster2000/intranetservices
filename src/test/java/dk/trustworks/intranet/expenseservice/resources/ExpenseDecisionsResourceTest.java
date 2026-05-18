package dk.trustworks.intranet.expenseservice.resources;

import dk.trustworks.intranet.expenseservice.model.Expense;
import dk.trustworks.intranet.expenseservice.model.ExpenseDecisionLog;
import io.quarkus.narayana.jta.QuarkusTransaction;
import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.security.TestSecurity;
import org.junit.jupiter.api.Test;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.UUID;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.*;

@QuarkusTest
class ExpenseDecisionsResourceTest {

    @Test
    @TestSecurity(user = "test-admin", roles = {"admin:write"})
    void list_returns_decisions_and_summary() {
        given()
            .header("X-Requested-By", "00000000-0000-0000-0000-000000000001")
            .queryParam("from", "2026-05-10")
            .queryParam("to",   "2026-05-17")
            .queryParam("limit", 50)
        .when()
            .get("/admin/expense-decisions")
        .then()
            .statusCode(200)
            .body("decisions",              notNullValue())
            .body("totalCount",             greaterThanOrEqualTo(0))
            .body("summary.autoApproved",   notNullValue())
            .body("summary.awaitingEmployee", notNullValue())
            .body("summary.sentToHr",       notNullValue());
    }

    @Test
    @TestSecurity(user = "outsider", roles = {"expenses:read"})
    void list_returns_403_without_admin_write() {
        given()
            .header("X-Requested-By", "00000000-0000-0000-0000-000000000001")
        .when()
            .get("/admin/expense-decisions")
        .then()
            .statusCode(403);
    }

    @Test
    @TestSecurity(user = "test-admin", roles = {"admin:write"})
    void list_prefers_extracted_merchant_and_falls_back_to_description() {
        LocalDate day = LocalDate.of(2099, 1, 10);
        String extracted = seedDecision(day, "Card text", "Receipt Merchant",
                "AI_VALIDATED_REJECTED", "NEEDS_JUSTIFICATION", "R_MEAL_COST_PER_PERSON");
        String fallback = seedDecision(day, "Fallback Merchant", "",
                "AI_VALIDATED_REJECTED", "NEEDS_JUSTIFICATION", "R_MEAL_COST_PER_PERSON");

        given()
            .header("X-Requested-By", "00000000-0000-0000-0000-000000000001")
            .queryParam("from", "2099-01-10")
            .queryParam("to",   "2099-01-10")
            .queryParam("limit", 20)
        .when()
            .get("/admin/expense-decisions")
        .then()
            .statusCode(200)
            .body("decisions.find { it.expenseUuid == '" + extracted + "' }.merchant", is("Receipt Merchant"))
            .body("decisions.find { it.expenseUuid == '" + fallback + "' }.merchant", is("Fallback Merchant"));
    }

    @Test
    @TestSecurity(user = "test-admin", roles = {"admin:write"})
    void list_maps_current_and_legacy_review_states_to_ui_outcomes() {
        LocalDate day = LocalDate.of(2099, 1, 11);
        String approved = seedDecision(day, "Approved", null,
                "AI_VALIDATED_APPROVED", null, null);
        String autoFix = seedDecision(day, "Auto fix", null,
                "AI_VALIDATED_REJECTED", "NEEDS_FIX", "R_RECEIPT_READABLE");
        String explain = seedDecision(day, "Explain", null,
                "AI_VALIDATED_REJECTED", "NEEDS_JUSTIFICATION", "R_MEAL_COST_PER_PERSON");
        String legacyExplain = seedDecision(day, "Legacy explain", null,
                "AI_VALIDATED_REJECTED", "AWAITING_EMPLOYEE_ACTION", "R_MEAL_COST_PER_PERSON");
        String rejected = seedDecision(day, "Rejected", null,
                "AI_VALIDATED_REJECTED", "PENDING_HR", "R_MEAL_COST_PER_PERSON");
        String legacyRejected = seedDecision(day, "Legacy rejected", null,
                "AI_VALIDATED_REJECTED", "PENDING_HR_REVIEW", "R_MEAL_COST_PER_PERSON");

        given()
            .header("X-Requested-By", "00000000-0000-0000-0000-000000000001")
            .queryParam("from", "2099-01-11")
            .queryParam("to",   "2099-01-11")
            .queryParam("limit", 20)
        .when()
            .get("/admin/expense-decisions")
        .then()
            .statusCode(200)
            .body("decisions.find { it.expenseUuid == '" + approved + "' }.outcome", is("APPROVED"))
            .body("decisions.find { it.expenseUuid == '" + autoFix + "' }.outcome", is("AUTO_FIX"))
            .body("decisions.find { it.expenseUuid == '" + explain + "' }.outcome", is("EXPLAIN"))
            .body("decisions.find { it.expenseUuid == '" + legacyExplain + "' }.outcome", is("EXPLAIN"))
            .body("decisions.find { it.expenseUuid == '" + rejected + "' }.outcome", is("REJECTED"))
            .body("decisions.find { it.expenseUuid == '" + legacyRejected + "' }.outcome", is("REJECTED"));
    }

    @Test
    @TestSecurity(user = "test-admin", roles = {"admin:write"})
    void list_filters_by_ui_outcome_not_raw_log_action() {
        LocalDate day = LocalDate.of(2099, 1, 12);
        String autoFix = seedDecision(day, "Auto fix", null,
                "AI_VALIDATED_REJECTED", "NEEDS_FIX", "R_RECEIPT_READABLE");
        String explain = seedDecision(day, "Explain", null,
                "AI_VALIDATED_REJECTED", "NEEDS_JUSTIFICATION", "R_MEAL_COST_PER_PERSON");
        String other = seedDecision(day, "Other", null,
                "HR_COMMENTED", null, null);

        given()
            .header("X-Requested-By", "00000000-0000-0000-0000-000000000001")
            .queryParam("from", "2099-01-12")
            .queryParam("to",   "2099-01-12")
            .queryParam("outcome", "AUTO_FIX")
            .queryParam("limit", 20)
        .when()
            .get("/admin/expense-decisions")
        .then()
            .statusCode(200)
            .body("decisions.expenseUuid", hasItem(autoFix))
            .body("decisions.expenseUuid", not(hasItem(explain)))
            .body("decisions.expenseUuid", not(hasItem(other)));

        given()
            .header("X-Requested-By", "00000000-0000-0000-0000-000000000001")
            .queryParam("from", "2099-01-12")
            .queryParam("to",   "2099-01-12")
            .queryParam("outcome", "OTHER")
            .queryParam("limit", 20)
        .when()
            .get("/admin/expense-decisions")
        .then()
            .statusCode(200)
            .body("decisions.expenseUuid", hasItem(other))
            .body("decisions.expenseUuid", not(hasItem(autoFix)))
            .body("decisions.expenseUuid", not(hasItem(explain)));
    }

    private String seedDecision(LocalDate day,
                                String description,
                                String extractedMerchant,
                                String action,
                                String toReviewState,
                                String ruleId) {
        String expenseUuid = UUID.randomUUID().toString();
        QuarkusTransaction.requiringNew().run(() -> {
            Expense e = new Expense();
            e.setUuid(expenseUuid);
            e.setUseruuid("decision-test-user");
            e.setAmount(123.0);
            e.setAccount("3585");
            e.setDescription(description);
            e.setExtractedMerchantName(extractedMerchant);
            e.setStatus("CREATED");
            e.setExpensedate(day);
            e.setDatecreated(day);
            e.setDatemodified(day);
            e.persist();

            ExpenseDecisionLog l = new ExpenseDecisionLog();
            l.uuid = UUID.randomUUID().toString();
            l.expenseUuid = expenseUuid;
            l.occurredAt = LocalDateTime.of(day, java.time.LocalTime.NOON);
            l.actorRole = "AI";
            l.action = action;
            l.fromStatus = "CREATED";
            l.toStatus = "AI_VALIDATED_APPROVED".equals(action) ? "VALIDATED" : "CREATED";
            l.toReviewState = toReviewState;
            l.aiRuleId = ruleId;
            l.reasonText = "seeded";
            l.persist();
        });
        return expenseUuid;
    }
}
