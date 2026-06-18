package dk.trustworks.intranet.expenseservice.services;

import dk.trustworks.intranet.expenseservice.model.Expense;
import io.quarkus.narayana.jta.QuarkusTransaction;
import io.quarkus.test.InjectMock;
import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.junit.QuarkusTestProfile;
import io.quarkus.test.junit.TestProfile;
import jakarta.inject.Inject;
import jakarta.ws.rs.BadRequestException;
import org.junit.jupiter.api.Test;

import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

@QuarkusTest
@TestProfile(ExpenseEconomicResendKillSwitchTest.UploadDisabled.class)
class ExpenseEconomicResendKillSwitchTest {

    public static class UploadDisabled implements QuarkusTestProfile {
        @Override public Map<String, String> getConfigOverrides() {
            return Map.of("dk.trustworks.expense.economics-upload.enabled", "false");
        }
    }

    @Inject ExpenseEconomicResendService service;
    @InjectMock EconomicsService economicsService;

    @Test
    void skipsWhenUploadDisabled() throws Exception {
        Expense e = new Expense();
        e.setUuid(UUID.randomUUID().toString());
        e.setUseruuid("user-1");
        e.setAccount("3585");
        e.setStatus("VERIFIED_UNBOOKED");
        e.setState("POSTED");
        e.setVouchernumber(5001);
        QuarkusTransaction.requiringNew().run(e::persist);

        BadRequestException ex = assertThrows(BadRequestException.class,
            () -> service.resendOne(e.getUuid(), "accountant-1"));
        assertEquals("e-conomic upload disabled in this environment", ex.getMessage());
        verify(economicsService, never()).sendVoucher(any(), any(), any());
    }
}
