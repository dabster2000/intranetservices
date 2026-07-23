package dk.trustworks.intranet.recruitmentservice.resources;

import io.quarkus.narayana.jta.QuarkusTransaction;
import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.security.TestSecurity;
import jakarta.inject.Inject;
import jakarta.persistence.EntityManager;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.equalTo;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * P13 §DoD — {@code GET /recruitment/slack/flags} returns the twelve
 * literal booleans (missing/false rows read false; a flipped setting is
 * visible on the next call — no caching, the flag idiom).
 */
@QuarkusTest
class RecruitmentSlackFlagsApiTest {

    private static final String MASTER_FLAG = "recruitment.slack.interactivity.enabled";
    private static final String LOOKUP_FLAG = "recruitment.slack.lookup.enabled";

    @Inject
    EntityManager em;

    private String previousMaster;
    private String previousLookup;
    private boolean touched;

    @AfterEach
    void restore() {
        if (touched) {
            QuarkusTransaction.requiringNew().run(() -> {
                P8ProfileFixtures.restoreFlag(em, MASTER_FLAG, previousMaster);
                P8ProfileFixtures.restoreFlag(em, LOOKUP_FLAG, previousLookup);
            });
        }
    }

    @Test
    @TestSecurity(user = "reader", roles = {"recruitment:read"})
    void flags_returnTwelveBooleans_defaultFalse_andFollowSettingsWithoutRestart() {
        QuarkusTransaction.requiringNew().run(() -> {
            previousMaster = P8ProfileFixtures.setFlag(em, MASTER_FLAG, "false");
            previousLookup = P8ProfileFixtures.setFlag(em, LOOKUP_FLAG, "false");
            touched = true;
        });

        var body = given().get("/recruitment/slack/flags")
                .then().statusCode(200)
                .body("interactivity", equalTo(false))
                .body("lookup", equalTo(false))
                .extract().body().jsonPath().getMap("$");
        assertEquals(12, body.size(), "exactly the twelve Slack toggles, nothing else");
        body.values().forEach(v -> assertTrue(v instanceof Boolean, "booleans only — no configuration detail leaks"));

        QuarkusTransaction.requiringNew().run(() -> {
            P8ProfileFixtures.setFlag(em, MASTER_FLAG, "true");
            P8ProfileFixtures.setFlag(em, LOOKUP_FLAG, "true");
        });

        given().get("/recruitment/slack/flags")
                .then().statusCode(200)
                .body("interactivity", equalTo(true))
                .body("lookup", equalTo(true))
                .body("assistant", equalTo(false));
    }

    @Test
    void anonymous_cannotReadFlags() {
        given().get("/recruitment/slack/flags")
                .then().statusCode(401);
    }
}
