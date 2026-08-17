package dk.trustworks.intranet.expenseservice.resources;

import dk.trustworks.intranet.expenseservice.dto.ExpenseReviewListItemDTO;
import dk.trustworks.intranet.expenseservice.model.Expense;
import org.junit.jupiter.api.Test;

import java.time.LocalDate;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Plain unit test (no Quarkus, no DB). Regression guard for the 2026-07-24 prod
 * incident: one NEEDS_ATTENTION expense with a null useruuid made toDTO() call
 * User.findById(null) — "IllegalArgumentException: Identifier may not be null" —
 * which 500'd every ACCOUNTING/OVERDUE/ALL review segment containing the row.
 * With the guard, the user lookup is skipped entirely, so this test needs no
 * Panache runtime: any findById invocation would throw here and fail the test.
 */
class ExpenseReviewResourceToDTOTest {

    private final ExpenseReviewResource resource = new ExpenseReviewResource();

    @Test
    void nullUseruuidRowMapsToDtoInsteadOfThrowing() {
        Expense e = new Expense();
        e.setUuid("00000000-0000-0000-0000-000000000001");
        e.setUseruuid(null);
        e.setDatemodified(LocalDate.now().minusDays(3));
        e.setEmployeeJustification("missing receipt");
        e.setAiRuleId("rule-7");

        ExpenseReviewListItemDTO dto = resource.toDTO(e);

        assertSame(e, dto.expense());
        assertNull(dto.employeeName());
        assertNull(dto.employeePhotoUrl());
        // toDTO computes its own LocalDate.now(); tolerate a midnight rollover between setup and call
        assertTrue(dto.daysWaiting() == 3 || dto.daysWaiting() == 4);
        assertEquals("missing receipt", dto.employeeJustification());
        assertEquals("rule-7", dto.aiRuleId());
        assertEquals(List.of(), dto.aiRuleIds());
        assertFalse(dto.hasVoucher());
    }

    @Test
    void daysWaitingAnchorsOnAttentionSince_notDatemodified() {
        // P0 regression: decision churn resets datemodified; the queue age must not follow.
        Expense e = new Expense();
        e.setUuid("00000000-0000-0000-0000-000000000003");
        e.setUseruuid(null);
        e.setDatemodified(LocalDate.now());                                  // churned today
        e.setAttentionSince(java.time.LocalDateTime.now().minusDays(30));    // entered a month ago
        e.setVouchernumber(4711);

        ExpenseReviewListItemDTO dto = resource.toDTO(e);

        assertTrue(dto.daysWaiting() == 30 || dto.daysWaiting() == 31,
                "age must come from attention_since, was " + dto.daysWaiting());
        assertTrue(dto.hasVoucher());
    }

    @Test
    void nullUseruuidAndNullDatesStillMap() {
        Expense e = new Expense();
        e.setUuid("00000000-0000-0000-0000-000000000002");
        e.setUseruuid(null);
        e.setDatemodified(null);
        e.setDatecreated(null);

        ExpenseReviewListItemDTO dto = resource.toDTO(e);

        assertNull(dto.employeeName());
        assertEquals(0, dto.daysWaiting());
    }
}
