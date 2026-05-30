package dk.trustworks.intranet.expenseservice.resources;

import dk.trustworks.intranet.expenseservice.model.Expense;
import dk.trustworks.intranet.expenseservice.model.ExpenseDecisionLog;
import dk.trustworks.intranet.expenseservice.model.ExpenseStateDeriver;
import dk.trustworks.intranet.expenseservice.services.ExpenseDecisionLogService;
import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.security.TestSecurity;
import jakarta.inject.Inject;
import jakarta.ws.rs.core.MediaType;
import org.junit.jupiter.api.Test;

import java.util.List;

import static io.restassured.RestAssured.given;
import static org.junit.jupiter.api.Assertions.*;

@QuarkusTest
class ExpenseReviewResourceDecisionsTest {

    @Inject ExpenseDecisionLogService logs;

    /** Accounting-owned exception (formerly PENDING_HR). */
    private String seedAccountingOwnedExpense() {
        return seedExpenseInState(ExpenseStateDeriver.OWNER_ACCOUNTING, ExpenseStateDeriver.KIND_POLICY);
    }

    /**
     * Seeds a NEEDS_ATTENTION row with the unified state/owner/kind written DIRECTLY.
     * Phase 3 retired the legacy review_state column, so the entity hook no longer derives
     * the unified triple from it — the decision preconditions read state/attentionOwner.
     */
    private String seedExpenseInState(String owner, String kind) {
        Expense e = new Expense();
        e.setUuid(java.util.UUID.randomUUID().toString());
        e.setUseruuid("u");
        e.setAmount(450.0);
        e.setAccount("3585");
        e.setExpensedate(java.time.LocalDate.now());
        e.setDatecreated(java.time.LocalDate.now());
        e.setStatus("CREATED");
        e.setState(ExpenseStateDeriver.NEEDS_ATTENTION);
        e.setAttentionOwner(owner);
        e.setAttentionKind(kind);
        e.setAiRuleId("R_MEAL_COST_PER_PERSON");
        e.setAiValidationApproved(false);
        e.setEmployeeJustification("client meeting");
        io.quarkus.narayana.jta.QuarkusTransaction.requiringNew().run(e::persist);
        return e.getUuid();
    }

    /** Most recent decision-log row for an expense (findByExpense is ordered occurredAt asc). */
    private ExpenseDecisionLog lastLog(String uuid) {
        List<ExpenseDecisionLog> rows = io.quarkus.narayana.jta.QuarkusTransaction.requiringNew()
            .call(() -> logs.findByExpense(uuid));
        assertFalse(rows.isEmpty(), "expected at least one decision-log row");
        return rows.get(rows.size() - 1);
    }

    @Test @TestSecurity(user = "hr", roles = {"expenses:review"})
    void approveAdvancesToValidatedAndLogs() {
        String uuid = seedAccountingOwnedExpense();
        given()
          .header("X-Requested-By", "hr")
          .contentType(MediaType.APPLICATION_JSON)
          .body("{\"reason\":\"Pre-approved by Lars\"}")
        .when()
          .post("/expenses/" + uuid + "/review/approve")
        .then()
          .statusCode(204);

        Expense after = io.quarkus.narayana.jta.QuarkusTransaction.requiringNew()
            .call(() -> Expense.findById(uuid));
        assertEquals("VALIDATED", after.getStatus());
        assertEquals(ExpenseStateDeriver.APPROVED, after.getState());
        assertNull(after.getAttentionOwner());
        assertNull(after.getAttentionKind());
        // Decision recorded in the audit log (replaces the legacy hr_decision* columns).
        ExpenseDecisionLog log = lastLog(uuid);
        assertEquals("HR_APPROVED", log.action);
        assertEquals("hr", log.actorUuid);
        assertEquals("Pre-approved by Lars", log.reasonText);
        assertEquals(1, logs.findByExpense(uuid).stream()
            .filter(l -> "HR_APPROVED".equals(l.action)).count());
    }

    @Test @TestSecurity(user = "hr", roles = {"expenses:review"})
    void sendBackTransitionsToEmployeeWithComment() {
        String uuid = seedAccountingOwnedExpense();
        given()
          .header("X-Requested-By", "hr")
          .contentType(MediaType.APPLICATION_JSON)
          .body("{\"comment\":\"Need attendee list\"}")
        .when()
          .post("/expenses/" + uuid + "/review/send-back")
        .then()
          .statusCode(204);

        Expense after = io.quarkus.narayana.jta.QuarkusTransaction.requiringNew()
            .call(() -> Expense.findById(uuid));
        assertEquals("CREATED", after.getStatus());
        assertEquals(ExpenseStateDeriver.NEEDS_ATTENTION, after.getState());
        assertEquals(ExpenseStateDeriver.OWNER_EMPLOYEE, after.getAttentionOwner());
        assertEquals(ExpenseStateDeriver.KIND_JUSTIFICATION, after.getAttentionKind());
        // Comment preserved in the audit log (replaces the legacy hr_comment column).
        ExpenseDecisionLog log = lastLog(uuid);
        assertEquals("HR_SENT_BACK", log.action);
        assertEquals("Need attendee list", log.reasonText);
    }

    @Test @TestSecurity(user = "hr", roles = {"expenses:review"})
    void rejectMarksDeleted() {
        String uuid = seedAccountingOwnedExpense();
        given()
          .header("X-Requested-By", "hr")
          .contentType(MediaType.APPLICATION_JSON)
          .body("{\"reason\":\"Not a reimbursable expense\"}")
        .when()
          .post("/expenses/" + uuid + "/review/reject")
        .then()
          .statusCode(204);

        Expense after = io.quarkus.narayana.jta.QuarkusTransaction.requiringNew()
            .call(() -> Expense.findById(uuid));
        assertEquals("DELETED", after.getStatus());
        assertEquals(ExpenseStateDeriver.REJECTED, after.getState());
        // Reason preserved in the audit log (replaces the legacy hr_comment column).
        ExpenseDecisionLog log = lastLog(uuid);
        assertEquals("HR_REJECTED", log.action);
        assertEquals("Not a reimbursable expense", log.reasonText);
    }

    @Test @TestSecurity(user = "hr", roles = {"expenses:review"})
    void approveOverridesAutoFixExpense() {
        String uuid = seedExpenseInState(ExpenseStateDeriver.OWNER_EMPLOYEE, ExpenseStateDeriver.KIND_RECEIPT);
        given()
          .header("X-Requested-By", "hr")
          .contentType(MediaType.APPLICATION_JSON)
          .body("{\"reason\":\"Receipt unreadable but expense verified by manager\"}")
        .when()
          .post("/expenses/" + uuid + "/review/approve")
        .then()
          .statusCode(204);

        Expense after = io.quarkus.narayana.jta.QuarkusTransaction.requiringNew()
            .call(() -> Expense.findById(uuid));
        assertEquals("VALIDATED", after.getStatus());
        assertEquals(ExpenseStateDeriver.APPROVED, after.getState());
        assertNull(after.getAttentionOwner());
        assertNull(after.getAttentionKind());
        ExpenseDecisionLog log = lastLog(uuid);
        assertEquals("HR_APPROVED", log.action);
        assertEquals("hr", log.actorUuid);
        assertEquals(1, logs.findByExpense(uuid).stream()
            .filter(l -> "HR_APPROVED".equals(l.action)).count());
    }

    @Test @TestSecurity(user = "hr", roles = {"expenses:review"})
    void approveOverridesNeedsJustificationExpense() {
        String uuid = seedExpenseInState(ExpenseStateDeriver.OWNER_EMPLOYEE, ExpenseStateDeriver.KIND_JUSTIFICATION);
        given()
          .header("X-Requested-By", "hr")
          .contentType(MediaType.APPLICATION_JSON)
          .body("{\"reason\":\"Pre-approved by partner\"}")
        .when()
          .post("/expenses/" + uuid + "/review/approve")
        .then()
          .statusCode(204);

        Expense after = io.quarkus.narayana.jta.QuarkusTransaction.requiringNew()
            .call(() -> Expense.findById(uuid));
        assertEquals("VALIDATED", after.getStatus());
        assertEquals(ExpenseStateDeriver.APPROVED, after.getState());
        assertNull(after.getAttentionOwner());
        ExpenseDecisionLog log = lastLog(uuid);
        assertEquals("HR_APPROVED", log.action);
    }

    @Test @TestSecurity(user = "hr", roles = {"expenses:review"})
    void sendBackRejectedWhenEmployeeOwned() {
        // send-back requires an ACCOUNTING-owned item; an employee-owned RECEIPT item is rejected
        // (400) and left untouched.
        String uuid = seedExpenseInState(ExpenseStateDeriver.OWNER_EMPLOYEE, ExpenseStateDeriver.KIND_RECEIPT);
        given()
          .header("X-Requested-By", "hr")
          .contentType(MediaType.APPLICATION_JSON)
          .body("{\"comment\":\"Need clearer photo\"}")
        .when()
          .post("/expenses/" + uuid + "/review/send-back")
        .then()
          .statusCode(400);

        Expense after = io.quarkus.narayana.jta.QuarkusTransaction.requiringNew()
            .call(() -> Expense.findById(uuid));
        assertEquals("CREATED", after.getStatus());
        assertEquals(ExpenseStateDeriver.NEEDS_ATTENTION, after.getState());
        assertEquals(ExpenseStateDeriver.OWNER_EMPLOYEE, after.getAttentionOwner());
        assertEquals(ExpenseStateDeriver.KIND_RECEIPT, after.getAttentionKind());
    }

    @Test @TestSecurity(user = "hr", roles = {"expenses:review"})
    void rejectWorksOnEmployeeOwnedItem() {
        // reject is owner-agnostic: any NEEDS_ATTENTION item can be rejected, including an
        // employee-owned RECEIPT item.
        String uuid = seedExpenseInState(ExpenseStateDeriver.OWNER_EMPLOYEE, ExpenseStateDeriver.KIND_RECEIPT);
        given()
          .header("X-Requested-By", "hr")
          .contentType(MediaType.APPLICATION_JSON)
          .body("{\"reason\":\"Should not be reimbursed\"}")
        .when()
          .post("/expenses/" + uuid + "/review/reject")
        .then()
          .statusCode(204);

        Expense after = io.quarkus.narayana.jta.QuarkusTransaction.requiringNew()
            .call(() -> Expense.findById(uuid));
        assertEquals("DELETED", after.getStatus());
        assertEquals(ExpenseStateDeriver.REJECTED, after.getState());
        assertNull(after.getAttentionOwner());
        ExpenseDecisionLog log = lastLog(uuid);
        assertEquals("HR_REJECTED", log.action);
        assertEquals("Should not be reimbursed", log.reasonText);
    }

    /**
     * Stranded pre-Phase-4 AI-rejected rows (V356 backfill) have status already
     * past CREATED — typically VERIFIED_UNBOOKED with a real voucher in e-conomic.
     * Approving them must NOT downgrade the status back to VALIDATED, or the upload
     * batch would re-process them and create a duplicate voucher.
     */
    @Test @TestSecurity(user = "hr", roles = {"expenses:review"})
    void approveOnAdvancedStatusDoesNotDowngradeStatus() {
        Expense e = new Expense();
        e.setUuid(java.util.UUID.randomUUID().toString());
        e.setUseruuid("u");
        e.setAmount(138.6);
        e.setAccount("3585");
        e.setExpensedate(java.time.LocalDate.now().minusDays(20));
        e.setDatecreated(java.time.LocalDate.now().minusDays(20));
        // Persist as a CREATED head with the accounting-owned exception state so the hook
        // preserves it (CREATED is head, not status-derived).
        e.setStatus("CREATED");
        e.setVouchernumber(6037617);
        e.setJournalnumber(16);
        e.setAccountingyear("2025_6_2026a");
        e.setState(ExpenseStateDeriver.NEEDS_ATTENTION);
        e.setAttentionOwner(ExpenseStateDeriver.OWNER_ACCOUNTING);
        e.setAttentionKind(ExpenseStateDeriver.KIND_POLICY);
        e.setAiValidationApproved(false);
        e.setAiValidationReason("R_OFFICE_FOOD_DRINK proximity");
        io.quarkus.narayana.jta.QuarkusTransaction.requiringNew().run(e::persist);
        String uuid = e.getUuid();
        // Advance status to VERIFIED_UNBOOKED via a bulk update, which bypasses the entity
        // @PreUpdate hook — mirrors a real stranded V356-backfilled row whose status moved
        // into the e-conomic tail while its unified state stayed NEEDS_ATTENTION.
        io.quarkus.narayana.jta.QuarkusTransaction.requiringNew().run(() ->
            Expense.update("status = ?1 where uuid = ?2", "VERIFIED_UNBOOKED", uuid));

        given()
          .header("X-Requested-By", "hr")
          .contentType(MediaType.APPLICATION_JSON)
          .body("{\"reason\":\"Stranded legacy row — voucher already in e-conomic\"}")
        .when()
          .post("/expenses/" + uuid + "/review/approve")
        .then()
          .statusCode(204);

        Expense after = io.quarkus.narayana.jta.QuarkusTransaction.requiringNew()
            .call(() -> Expense.findById(uuid));
        assertEquals("VERIFIED_UNBOOKED", after.getStatus(),
            "Status must NOT be downgraded — the expense is already in e-conomic");
        assertEquals(ExpenseStateDeriver.POSTED, after.getState(),
            "VERIFIED_UNBOOKED is status-derived; the @PreUpdate hook re-derives POSTED, overwriting the APPROVED head write");
        assertNull(after.getAttentionOwner());
        ExpenseDecisionLog log = lastLog(uuid);
        assertEquals("HR_APPROVED", log.action);
        assertEquals("hr", log.actorUuid);
    }
}
