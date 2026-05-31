package dk.trustworks.intranet.expenseservice.resources;

import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.security.TestSecurity;
import org.junit.jupiter.api.Test;

import java.util.Map;
import java.util.UUID;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.is;

@QuarkusTest
class MobileDeviceCredentialResourceTest {

    // The test DB is a persistent local MariaDB (no clean-at-start) and credential_id
    // is UNIQUE, so every test generates fresh random ids to stay idempotent across
    // re-runs and to keep the active-credential count assertions deterministic.
    private Map<String, Object> sampleBody(String user, String credId) {
        return Map.of(
            "userUuid",     user,
            "credentialId", credId,
            "publicKey",    "cHVibGljLWtleS1iNjR1cmw",
            "signCount",    0,
            "deviceLabel",  "iPhone Test",
            "transports",   "internal"
        );
    }

    @Test
    @TestSecurity(user = "system", roles = {"expenses:read", "expenses:write"})
    void create_list_fetch_revoke_lifecycle() {
        String user   = UUID.randomUUID().toString();
        String credId = UUID.randomUUID().toString();

        // create
        given().contentType("application/json").body(sampleBody(user, credId))
        .when().post("/expenses/mobile/device-credentials")
        .then().statusCode(201)
            .body("credentialId", equalTo(credId))
            .body("credentialEpoch", is(0));

        // list active for user
        given().when().get("/expenses/mobile/device-credentials?userUuid=" + user)
        .then().statusCode(200)
            .body("credentials.size()", is(1));

        // fetch by credentialId
        given().when().get("/expenses/mobile/device-credentials/" + credId)
        .then().statusCode(200)
            .body("userUuid", equalTo(user))
            .body("active", is(true));

        // revoke
        given().when().delete("/expenses/mobile/device-credentials/" + credId)
        .then().statusCode(204);

        // after revoke: still fetchable but inactive, epoch bumped, and absent from active list
        given().when().get("/expenses/mobile/device-credentials/" + credId)
        .then().statusCode(200)
            .body("active", is(false))
            .body("credentialEpoch", is(1));

        given().when().get("/expenses/mobile/device-credentials?userUuid=" + user)
        .then().statusCode(200)
            .body("credentials.size()", is(0));
    }

    @Test
    @TestSecurity(user = "system", roles = {"expenses:read", "expenses:write"})
    void update_counter_succeeds() {
        String user   = UUID.randomUUID().toString();
        String credId = UUID.randomUUID().toString();
        given().contentType("application/json").body(sampleBody(user, credId))
        .when().post("/expenses/mobile/device-credentials").then().statusCode(201);

        given().contentType("application/json").body(Map.of("signCount", 7))
        .when().put("/expenses/mobile/device-credentials/" + credId + "/counter")
        .then().statusCode(200).body("signCount", is(7));
    }

    @Test
    @TestSecurity(user = "system", roles = {"expenses:read", "expenses:write"})
    void fetch_unknown_credential_returns_404() {
        given().when().get("/expenses/mobile/device-credentials/does-not-exist-" + UUID.randomUUID())
        .then().statusCode(404);
    }
}
