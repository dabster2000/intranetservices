package dk.trustworks.intranet.expenseservice.resources;

import dk.trustworks.intranet.expenseservice.model.Expense;
import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.security.TestSecurity;
import jakarta.ws.rs.core.MediaType;
import org.junit.jupiter.api.Test;

import static io.restassured.RestAssured.given;
import static org.junit.jupiter.api.Assertions.*;

@QuarkusTest
class ExpenseResourceUpdateAuthTest {

    String seedExpense(String userUuid) {
        Expense e = new Expense();
        e.setUuid(java.util.UUID.randomUUID().toString());
        e.setUseruuid(userUuid);
        e.setAmount(200.0);
        e.setAccount("3585");
        e.setAccountname("Personaleudgifter");
        e.setDescription("original description");
        e.setExpensedate(java.time.LocalDate.now());
        e.setDatecreated(java.time.LocalDate.now());
        e.setStatus("CREATED");
        io.quarkus.narayana.jta.QuarkusTransaction.requiringNew().run(e::persist);
        return e.getUuid();
    }

    @Test @TestSecurity(user = "stranger", roles = {"expenses:write"})
    void nonOwnerForbidden() {
        String uuid = seedExpense("user-1");
        given()
          .header("X-Requested-By", "stranger")
          .contentType(MediaType.APPLICATION_JSON)
          .body("{\"description\":\"hijacked\"}")
        .when()
          .put("/expenses/" + uuid)
        .then()
          .statusCode(403);

        Expense after = io.quarkus.narayana.jta.QuarkusTransaction.requiringNew()
            .call(() -> Expense.findById(uuid));
        assertEquals("original description", after.getDescription());
    }

    @Test @TestSecurity(user = "user-1", roles = {"expenses:write"})
    void ownerCanUpdate() {
        String uuid = seedExpense("user-1");
        given()
          .header("X-Requested-By", "user-1")
          .contentType(MediaType.APPLICATION_JSON)
          .body("{\"description\":\"updated by owner\"}")
        .when()
          .put("/expenses/" + uuid)
        .then()
          .statusCode(204);

        Expense after = io.quarkus.narayana.jta.QuarkusTransaction.requiringNew()
            .call(() -> Expense.findById(uuid));
        assertEquals("updated by owner", after.getDescription());
    }

    @Test @TestSecurity(user = "hr-user", roles = {"expenses:write", "expenses:review"})
    void accountingReviewerCanUpdateOthersExpense() {
        String uuid = seedExpense("user-1");
        given()
          .header("X-Requested-By", "hr-user")
          .contentType(MediaType.APPLICATION_JSON)
          .body("{\"description\":\"corrected by accounting\"}")
        .when()
          .put("/expenses/" + uuid)
        .then()
          .statusCode(204);

        Expense after = io.quarkus.narayana.jta.QuarkusTransaction.requiringNew()
            .call(() -> Expense.findById(uuid));
        assertEquals("corrected by accounting", after.getDescription());
    }
}
