package dk.trustworks.intranet.recruitmentservice.api;

import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.security.TestSecurity;
import org.junit.jupiter.api.Test;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.anyOf;
import static org.hamcrest.Matchers.is;

/**
 * CI-only @QuarkusTest for GET /api/recruitment/interviews/{uuid}/integrations/status.
 * Sandbox-blocked locally — needs full Quarkus boot.
 */
@QuarkusTest
class IntegrationStatusResourceTest {

    @Test
    @TestSecurity(user = "tam", roles = {"recruitment:read"})
    void returns_status_or_404_for_unknown_uuid() {
        given().header("X-Requested-By", "u-tam")
               .when().get("/api/recruitment/interviews/test-iv-known/integrations/status")
               .then().statusCode(anyOf(is(200), is(404)));
    }

    @Test
    @TestSecurity(user = "stranger", roles = {"recruitment:read"})
    void returns_404_when_record_access_denies() {
        given().header("X-Requested-By", "u-stranger")
               .when().get("/api/recruitment/interviews/test-iv-not-mine/integrations/status")
               .then().statusCode(404);
    }
}
