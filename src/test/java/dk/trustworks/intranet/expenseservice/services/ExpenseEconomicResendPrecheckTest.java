package dk.trustworks.intranet.expenseservice.services;

import dk.trustworks.intranet.expenseservice.dto.ExpenseResendPrecheckDTO;
import dk.trustworks.intranet.expenseservice.model.Expense;
import dk.trustworks.intranet.expenseservice.model.UserAccount;
import io.quarkus.narayana.jta.QuarkusTransaction;
import io.quarkus.test.InjectMock;
import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import org.junit.jupiter.api.Test;

import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

@QuarkusTest
class ExpenseEconomicResendPrecheckTest {

    @Inject ExpenseEconomicResendService service;
    @InjectMock EconomicsService economicsService;

    private Expense seedPosted() {
        String user = "user-" + UUID.randomUUID();
        QuarkusTransaction.requiringNew().run(() -> {
            UserAccount ua = new UserAccount();
            ua.setUseruuid(user);
            ua.setEconomics(24649);
            ua.setUsername("ext_test");
            ua.persist();
        });
        Expense e = new Expense();
        e.setUuid(UUID.randomUUID().toString());
        e.setUseruuid(user);
        e.setAccount("3585");
        e.setStatus("VERIFIED_UNBOOKED");
        e.setState("POSTED");
        e.setVouchernumber(5001);
        e.setJournalnumber(1);
        e.setAccountingyear("2025_6_2026");
        QuarkusTransaction.requiringNew().run(e::persist);
        return e;
    }

    @Test
    void reportsMissingWhenNeitherDraftNorBooked() {
        Expense e = seedPosted();
        when(economicsService.checkVoucherExists(any())).thenReturn(EconomicsService.VoucherLookupResult.NOT_FOUND);
        when(economicsService.checkVoucherBooked(any())).thenReturn(EconomicsService.VoucherLookupResult.NOT_FOUND);

        ExpenseResendPrecheckDTO dto = service.precheckOne(e.getUuid());
        assertTrue(dto.eligible());
        assertFalse(dto.voucherExists());
        assertEquals("MISSING", dto.location());
    }

    @Test
    void reportsBookedWhenLedgerHasIt() {
        Expense e = seedPosted();
        when(economicsService.checkVoucherExists(any())).thenReturn(EconomicsService.VoucherLookupResult.NOT_FOUND);
        when(economicsService.checkVoucherBooked(any())).thenReturn(EconomicsService.VoucherLookupResult.FOUND);

        ExpenseResendPrecheckDTO dto = service.precheckOne(e.getUuid());
        assertTrue(dto.voucherExists());
        assertEquals("BOOKED", dto.location());
    }

    @Test
    void reportsUnknownWhenLookupFails() {
        // 2026-08-12 regression guard: an e-conomic 5xx must NOT precheck as MISSING —
        // that invited a duplicate re-send of a voucher that was merely unreachable.
        Expense e = seedPosted();
        when(economicsService.checkVoucherExists(any())).thenReturn(EconomicsService.VoucherLookupResult.UNKNOWN);
        when(economicsService.checkVoucherBooked(any())).thenReturn(EconomicsService.VoucherLookupResult.UNKNOWN);

        ExpenseResendPrecheckDTO dto = service.precheckOne(e.getUuid());
        assertFalse(dto.eligible());
        assertFalse(dto.voucherExists());
        assertEquals("UNKNOWN", dto.location());
    }

    @Test
    void reportsIneligibleForNeverPosted() {
        Expense e = new Expense();
        e.setUuid(UUID.randomUUID().toString());
        e.setUseruuid("user-x");
        e.setAccount("3585");
        e.setStatus("CREATED");
        e.setState("SUBMITTED");
        QuarkusTransaction.requiringNew().run(e::persist);

        ExpenseResendPrecheckDTO dto = service.precheckOne(e.getUuid());
        assertFalse(dto.eligible());
        assertEquals("not posted yet", dto.reason());
    }
}
