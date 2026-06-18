package dk.trustworks.intranet.expenseservice.resources;

import dk.trustworks.intranet.dto.ExpenseFile;
import dk.trustworks.intranet.expenseservice.model.Expense;
import dk.trustworks.intranet.expenseservice.model.UserAccount;
import dk.trustworks.intranet.expenseservice.services.EconomicsService;
import dk.trustworks.intranet.expenseservice.services.ExpenseFileService;
import io.quarkus.narayana.jta.QuarkusTransaction;
import io.quarkus.test.InjectMock;
import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.security.TestSecurity;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;
import org.junit.jupiter.api.Test;

import java.util.UUID;

import static io.restassured.RestAssured.given;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

@QuarkusTest
class ExpenseEconomicsResendResourceTest {

    @InjectMock EconomicsService economicsService;
    @InjectMock ExpenseFileService expenseFileService;

    private String seedPosted() {
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
        e.setExpensedate(java.time.LocalDate.now());
        e.setStatus("VERIFIED_UNBOOKED");
        e.setState("POSTED");
        e.setVouchernumber(5001);
        e.setJournalnumber(1);
        e.setAccountingyear("2025_6_2026");
        QuarkusTransaction.requiringNew().run(e::persist);
        return e.getUuid();
    }

    @Test
    @TestSecurity(user = "accountant", roles = {"expenses:review"})
    void resendReturnsUpdatedCount() throws Exception {
        String uuid = seedPosted();
        when(economicsService.sendVoucher(any(), any(), any())).thenReturn(Response.ok().build());
        when(expenseFileService.getFileById(any())).thenReturn(new ExpenseFile(uuid, "BASE64"));

        given()
          .header("X-Requested-By", "accountant")
          .contentType(MediaType.APPLICATION_JSON)
          .body("{\"uuids\":[\"" + uuid + "\"]}")
        .when()
          .post("/expenses/economics/resend")
        .then()
          .statusCode(200)
          .body("updated", org.hamcrest.Matchers.is(1));
    }

    @Test
    @TestSecurity(user = "intruder", roles = {"expenses:read"})
    void resendForbiddenWithoutReviewScope() {
        given()
          .header("X-Requested-By", "intruder")
          .contentType(MediaType.APPLICATION_JSON)
          .body("{\"uuids\":[\"any\"]}")
        .when()
          .post("/expenses/economics/resend")
        .then()
          .statusCode(403);
    }
}
