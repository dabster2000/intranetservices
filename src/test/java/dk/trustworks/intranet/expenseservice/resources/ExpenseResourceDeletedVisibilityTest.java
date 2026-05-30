package dk.trustworks.intranet.expenseservice.resources;

import dk.trustworks.intranet.expenseservice.model.Expense;
import io.quarkus.narayana.jta.QuarkusTransaction;
import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.security.TestSecurity;
import org.junit.jupiter.api.Test;

import java.time.LocalDate;
import java.util.UUID;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.anyOf;
import static org.hamcrest.Matchers.hasItem;
import static org.hamcrest.Matchers.is;
import static org.hamcrest.Matchers.not;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

@QuarkusTest
class ExpenseResourceDeletedVisibilityTest {

    private Expense seedExpense(String useruuid, String projectuuid, String status) {
        Expense e = new Expense();
        e.setUuid(UUID.randomUUID().toString());
        e.setUseruuid(useruuid);
        e.setProjectuuid(projectuuid);
        e.setAmount(100.0);
        e.setAccount("3585");
        e.setExpensedate(LocalDate.of(2099, 5, 20));
        e.setDatecreated(LocalDate.of(2099, 5, 20));
        e.setDatemodified(LocalDate.of(2099, 5, 20));
        e.setStatus(status);
        QuarkusTransaction.requiringNew().run(e::persist);
        return e;
    }

    @Test
    @TestSecurity(user = "reader", roles = {"expenses:read"})
    void listAndPeriodEndpointsExcludeDeletedRows() {
        String useruuid = "user-" + UUID.randomUUID();
        String projectuuid = "project-" + UUID.randomUUID();
        Expense visible = seedExpense(useruuid, projectuuid, "CREATED");
        Expense deleted = seedExpense(useruuid, projectuuid, "DELETED");

        given()
          .queryParam("limit", "20")
          .queryParam("page", "0")
          .queryParam("includeDeleted", "true")
        .when()
          .get("/expenses/user/" + useruuid)
        .then()
          .statusCode(200)
          .body("uuid", hasItem(visible.getUuid()))
          .body("uuid", not(hasItem(deleted.getUuid())));

        given()
          .queryParam("fromdate", "2099-05-01")
          .queryParam("todate", "2099-05-31")
        .when()
          .get("/expenses/user/" + useruuid + "/search/period")
        .then()
          .statusCode(200)
          .body("uuid", hasItem(visible.getUuid()))
          .body("uuid", not(hasItem(deleted.getUuid())));

        given()
          .queryParam("fromdate", "2099-05-01")
          .queryParam("todate", "2099-05-31")
        .when()
          .get("/expenses/project/" + projectuuid + "/search/period")
        .then()
          .statusCode(200)
          .body("uuid", hasItem(visible.getUuid()))
          .body("uuid", not(hasItem(deleted.getUuid())));

        given()
          .queryParam("fromdate", "2099-05-01")
          .queryParam("todate", "2099-05-31")
        .when()
          .get("/expenses/search/period")
        .then()
          .statusCode(200)
          .body("uuid", hasItem(visible.getUuid()))
          .body("uuid", not(hasItem(deleted.getUuid())));
    }

    @Test
    @TestSecurity(user = "writer", roles = {"expenses:write"})
    void deleteMovesToDeletedTerminalState() {
        Expense expense = seedExpense("user-" + UUID.randomUUID(), "", "CREATED");

        given()
        .when()
          .delete("/expenses/" + expense.getUuid())
        .then()
          .statusCode(anyOf(is(200), is(204)));

        Expense after = QuarkusTransaction.requiringNew()
                .call(() -> Expense.findById(expense.getUuid()));
        assertEquals("DELETED", after.getStatus());
        assertEquals("DELETED", after.getState());
        assertNull(after.getAttentionOwner());
    }
}
