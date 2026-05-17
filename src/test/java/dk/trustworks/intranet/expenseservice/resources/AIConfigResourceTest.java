package dk.trustworks.intranet.expenseservice.resources;

import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.security.TestSecurity;
import jakarta.ws.rs.core.MediaType;
import org.junit.jupiter.api.Test;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.*;

@QuarkusTest
class AIConfigResourceTest {

    @Test @TestSecurity(user = "admin", roles = {"admin:write"})
    void listRulesReturnsAll() {
        given()
            .header("X-Requested-By", "admin")
        .when()
            .get("/admin/ai-config/rules")
        .then()
            .statusCode(200)
            .body("size()", greaterThanOrEqualTo(13));
    }

    @Test @TestSecurity(user = "admin", roles = {"admin:write"})
    void updateParameterRefreshesValue() {
        given()
          .header("X-Requested-By", "admin")
          .contentType(MediaType.APPLICATION_JSON)
          .body("{\"value\":\"200\"}")
        .when()
          .put("/admin/ai-config/parameters/meal_cost_per_person_dkk")
        .then()
          .statusCode(200);

        given()
            .header("X-Requested-By", "admin")
        .when()
            .get("/admin/ai-config/parameters")
        .then()
            .statusCode(200)
            .body("find { it.key == 'meal_cost_per_person_dkk' }.value", is("200"));

        // restore default
        given()
            .header("X-Requested-By", "admin")
            .contentType(MediaType.APPLICATION_JSON)
            .body("{\"value\":\"125\"}")
        .when()
            .put("/admin/ai-config/parameters/meal_cost_per_person_dkk")
        .then()
            .statusCode(200);
    }

    @Test
    void unauthenticatedForbidden() {
        given()
        .when()
            .get("/admin/ai-config/rules")
        .then()
            .statusCode(anyOf(is(401), is(403)));
    }
}
