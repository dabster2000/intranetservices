package dk.trustworks.intranet.expenseservice.resources;

import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.security.TestSecurity;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.notNullValue;

@QuarkusTest
class MerchantAllowListResourceTest {

    @Test
    @TestSecurity(user = "test-admin", roles = {"admin:write"})
    void add_and_list_entries() {
        var add = Map.of(
            "ruleId",              "R_MERCHANT_ALLOW_OFFICE_FOOD_DRINK",
            "merchantNamePattern", "Café Det Sker",
            "matchKind",           "CONTAINS"
        );

        given()
            .header("X-Requested-By", "00000000-0000-0000-0000-000000000001")
            .contentType("application/json")
            .body(add)
        .when()
            .post("/admin/merchant-allow-list")
        .then()
            .statusCode(201);

        given()
            .header("X-Requested-By", "00000000-0000-0000-0000-000000000001")
        .when()
            .get("/admin/merchant-allow-list")
        .then()
            .statusCode(200)
            .body("entries", notNullValue());
    }
}
