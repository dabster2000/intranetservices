// intranetservices/src/test/java/dk/trustworks/intranet/expenseservice/services/ExpenseDecisionLogServiceResendTest.java
package dk.trustworks.intranet.expenseservice.services;

import dk.trustworks.intranet.expenseservice.model.Expense;
import dk.trustworks.intranet.expenseservice.model.ExpenseDecisionLog;
import io.quarkus.narayana.jta.QuarkusTransaction;
import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import org.junit.jupiter.api.Test;

import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

@QuarkusTest
class ExpenseDecisionLogServiceResendTest {

    @Inject ExpenseDecisionLogService logs;

    @Test
    void recordsEconomicResendRow() {
        Expense e = new Expense();
        e.setUuid(UUID.randomUUID().toString());
        e.setUseruuid("user-1");
        e.setStatus("VERIFIED_UNBOOKED");
        e.setState("POSTED");

        logs.recordEconomicResend(e, "accountant-1", "Re-sent to e-conomic: voucher 5001 -> 5042");

        ExpenseDecisionLog row = QuarkusTransaction.requiringNew().call(() ->
            ExpenseDecisionLog.find("expenseUuid = ?1 and action = ?2", e.getUuid(), "ECONOMIC_RESEND").firstResult());
        assertNotNull(row, "an ECONOMIC_RESEND row must be written");
        assertEquals("accountant-1", row.actorUuid);
        assertEquals("VERIFIED_UNBOOKED", row.toStatus, "status is unchanged by re-send");
        assertEquals("POSTED", row.toReviewState, "state is unchanged by re-send");
    }
}
