package dk.trustworks.intranet.recruitmentservice.api;

import io.quarkus.test.junit.QuarkusTest;
import org.junit.jupiter.api.Test;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.equalTo;

/**
 * CI-only @QuarkusTest for the public Microsoft Graph webhook
 * POST /api/recruitment/integrations/graph/notifications.
 * Verifies handshake echo + silent drop on bad clientState.
 */
@QuarkusTest
class GraphNotificationResourceE2eTest {

    @Test
    void handshake_returns_validation_token_as_text_plain() {
        given().queryParam("validationToken", "echo-me")
               .when().post("/api/recruitment/integrations/graph/notifications")
               .then().statusCode(200).body(equalTo("echo-me"));
    }

    @Test
    void notification_with_bad_clientState_is_silently_dropped_returns_200() {
        String payload = "{\"value\":[{\"clientState\":\"wrong\",\"resourceData\":{\"id\":\"evt-x\"}}]}";
        given().contentType("application/json").body(payload)
               .when().post("/api/recruitment/integrations/graph/notifications")
               .then().statusCode(200);
    }
}
