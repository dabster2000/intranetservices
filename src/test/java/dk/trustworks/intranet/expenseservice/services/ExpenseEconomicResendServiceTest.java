package dk.trustworks.intranet.expenseservice.services;

import dk.trustworks.intranet.dto.ExpenseFile;
import dk.trustworks.intranet.expenseservice.model.Expense;
import dk.trustworks.intranet.expenseservice.model.ExpenseDecisionLog;
import dk.trustworks.intranet.expenseservice.model.UserAccount;
import io.quarkus.narayana.jta.QuarkusTransaction;
import io.quarkus.test.InjectMock;
import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import jakarta.ws.rs.BadRequestException;
import jakarta.ws.rs.NotFoundException;
import jakarta.ws.rs.core.Response;
import org.junit.jupiter.api.Test;

import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

@QuarkusTest
class ExpenseEconomicResendServiceTest {

    @Inject ExpenseEconomicResendService service;
    @InjectMock EconomicsService economicsService;
    @InjectMock ExpenseFileService expenseFileService;

    private Expense seedPosted(String useruuid) {
        Expense e = new Expense();
        e.setUuid(UUID.randomUUID().toString());
        e.setUseruuid(useruuid);
        e.setAmount(250.0);
        e.setAccount("3585");
        e.setExpensedate(java.time.LocalDate.now());
        e.setDatecreated(java.time.LocalDate.now());
        e.setStatus("VERIFIED_UNBOOKED");  // posted to e-conomic
        e.setState("POSTED");
        e.setVouchernumber(5001);
        e.setJournalnumber(1);
        e.setAccountingyear("2025_6_2026");
        QuarkusTransaction.requiringNew().run(e::persist);
        return e;
    }

    private void seedUserAccount(String useruuid) {
        QuarkusTransaction.requiringNew().run(() -> {
            UserAccount ua = new UserAccount();
            ua.setUseruuid(useruuid);
            ua.setEconomics(24649);
            ua.setUsername("ext_test");
            ua.persist();
        });
    }

    @Test
    void resendPostsFreshVoucherWithoutChangingStatus() throws Exception {
        String user = "user-" + UUID.randomUUID();
        Expense e = seedPosted(user);
        seedUserAccount(user);
        when(expenseFileService.getFileById(e.getUuid())).thenReturn(new ExpenseFile(e.getUuid(), "BASE64"));
        // Assert isOrphaned is TRUE at call time (proves a fresh idempotency key) + simulate the new voucher.
        when(economicsService.sendVoucher(any(), any(), any())).thenAnswer(inv -> {
            Expense arg = inv.getArgument(0);
            assertTrue(Boolean.TRUE.equals(arg.getIsOrphaned()),
                    "isOrphaned must be true when sendVoucher runs (forces a new voucher)");
            arg.setVouchernumber(5042);
            return Response.ok().build();
        });

        service.resendOne(e.getUuid(), "accountant-1");

        Expense after = QuarkusTransaction.requiringNew().call(() -> Expense.findById(e.getUuid()));
        assertEquals("VERIFIED_UNBOOKED", after.getStatus(), "status unchanged");
        assertEquals("POSTED", after.getState(), "state unchanged");
        assertEquals(5042, after.getVouchernumber(), "voucher relinked to the new voucher");
        assertFalse(Boolean.TRUE.equals(after.getIsOrphaned()), "orphan flag cleared after success");
        assertEquals(1, after.getSafeRetryCount(), "retry count incremented once");
        verify(economicsService, times(1)).sendVoucher(any(), any(), any());

        ExpenseDecisionLog row = QuarkusTransaction.requiringNew().call(() ->
            ExpenseDecisionLog.find("expenseUuid = ?1 and action = ?2", e.getUuid(), "ECONOMIC_RESEND").firstResult());
        assertNotNull(row, "audit row written");
    }

    @Test
    void skipsExpenseNotYetPosted() throws Exception {
        String user = "user-" + UUID.randomUUID();
        Expense e = new Expense();
        e.setUuid(UUID.randomUUID().toString());
        e.setUseruuid(user);
        e.setAccount("3585");
        e.setExpensedate(java.time.LocalDate.now());
        e.setStatus("CREATED");   // never posted
        e.setState("SUBMITTED");
        QuarkusTransaction.requiringNew().run(e::persist);

        BadRequestException ex = assertThrows(BadRequestException.class,
            () -> service.resendOne(e.getUuid(), "accountant-1"));
        assertEquals("not posted yet", ex.getMessage());
        verify(economicsService, never()).sendVoucher(any(), any(), any());
    }

    @Test
    void throwsNotFoundForUnknownUuid() {
        assertThrows(NotFoundException.class, () -> service.resendOne("does-not-exist", "accountant-1"));
    }
}
