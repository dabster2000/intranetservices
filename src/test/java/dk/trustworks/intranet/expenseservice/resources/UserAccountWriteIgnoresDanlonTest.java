package dk.trustworks.intranet.expenseservice.resources;

import dk.trustworks.intranet.domain.user.entity.UserDanlonHistory;
import dk.trustworks.intranet.expenseservice.model.UserAccount;
import io.quarkus.narayana.jta.QuarkusTransaction;
import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.security.TestSecurity;
import jakarta.ws.rs.core.MediaType;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.time.LocalDate;
import java.util.UUID;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.*;
import static org.junit.jupiter.api.Assertions.assertEquals;

@QuarkusTest
class UserAccountWriteIgnoresDanlonTest {

    private String user;

    @AfterEach
    void cleanup() {
        if (user != null) {
            String u = user;
            QuarkusTransaction.requiringNew().run(() -> {
                UserDanlonHistory.delete("useruuid", u);
                UserAccount.deleteById(u);
            });
        }
    }

    @Test
    @TestSecurity(user = "admin", roles = {"expenses:read", "expenses:write"})
    void updatingAccountWithDanlonDoesNotWriteHistory() {
        user = UUID.randomUUID().toString();
        QuarkusTransaction.requiringNew().run(() -> {
            UserAccount acc = new UserAccount();
            acc.setUseruuid(user);
            acc.setEconomics(-1);
            acc.setUsername("acc_" + user.substring(0, 8));
            acc.persist();
            // one historical row, OPEN
            new UserDanlonHistory(user, LocalDate.of(2024, 1, 1), "T100", "seed").persist();
        });

        // PUT a danlon value via the DTO — it must be ignored now (Finding G removed).
        given().header("X-Requested-By", "admin")
                .contentType(MediaType.APPLICATION_JSON)
                .body("{\"useruuid\":\"" + user + "\",\"economics\":-1,\"username\":\"acc\",\"danlon\":\"T99999\"}")
        .when()
                .put("/user-accounts/" + user)
        .then()
                .statusCode(anyOf(is(200), is(204)));

        long count = QuarkusTransaction.requiringNew().call(() -> UserDanlonHistory.count("useruuid", user));
        assertEquals(1L, count, "no new Danløn history row may be written via the account endpoint");
    }
}
