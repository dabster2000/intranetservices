package dk.trustworks.intranet.recruitmentservice.api;

import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.security.TestSecurity;
import org.junit.jupiter.api.Test;

import static io.restassured.RestAssured.given;

/**
 * CI-only @QuarkusTest for POST /api/recruitment/interviews/{uuid}/integrations/retry.
 * Sandbox-blocked locally — relies on full Quarkus boot + cvtool/recruitment config bundle.
 */
@QuarkusTest
class IntegrationRetryResourceTest {

    @Test
    @TestSecurity(user = "tam", roles = {"recruitment:write", "recruitment:admin"})
    void rejects_invalid_kind_with_400() {
        given().header("X-Requested-By", "u-tam")
               .contentType("application/json")
               .body("{\"kind\":\"AI_GENERATE\"}")
               .when().post("/api/recruitment/interviews/iv-x/integrations/retry")
               .then().statusCode(400);
    }

    @Test
    @TestSecurity(user = "tam", roles = {"recruitment:write"})
    void rejects_fallback_when_kind_is_not_update_with_400() {
        given().header("X-Requested-By", "u-tam")
               .contentType("application/json")
               .body("{\"kind\":\"OUTLOOK_EVENT_CREATE\",\"fallback\":\"cancel-recreate\"}")
               .when().post("/api/recruitment/interviews/iv-x/integrations/retry")
               .then().statusCode(400);
    }

    @Test
    @TestSecurity(user = "stranger", roles = {"recruitment:write"})
    void returns_404_when_record_access_denies() {
        given().header("X-Requested-By", "u-stranger")
               .contentType("application/json")
               .body("{\"kind\":\"OUTLOOK_EVENT_CREATE\"}")
               .when().post("/api/recruitment/interviews/iv-not-mine/integrations/retry")
               .then().statusCode(404);
    }
}
