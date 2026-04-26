package dk.trustworks.intranet.recruitmentservice.api;

import dk.trustworks.intranet.recruitmentservice.AiEnabledTestProfile;
import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.junit.TestProfile;
import io.quarkus.test.security.TestSecurity;
import org.junit.jupiter.api.Test;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.notNullValue;
import static org.hamcrest.Matchers.oneOf;
import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * Resource-level test for {@link OpenRoleAiResource} (spec §6.2).
 *
 * <p>Uses {@link AiEnabledTestProfile} so {@code recruitment.ai.enabled} and
 * {@code recruitment.ai.role-brief.enabled} are both true; otherwise
 * {@link dk.trustworks.intranet.recruitmentservice.application.AiArtifactService#requestArtifact}
 * refuses with 503 Service Unavailable.
 *
 * <p>Three asserts cover the spec contract:
 * <ul>
 *   <li>Authorised callers receive 202 Accepted plus the persisted artifact UUID.</li>
 *   <li>Callers without {@code recruitment:write} are rejected with 403.</li>
 *   <li>Two consecutive triggers for the same role yield the <em>same</em> artifact
 *       UUID (idempotency via the {@code input_digest} unique key in
 *       {@code recruitment_ai_artifact}).</li>
 * </ul>
 */
@QuarkusTest
@TestProfile(AiEnabledTestProfile.class)
class OpenRoleAiResourceTest {

    private static final String TAM_UUID = "00000000-0000-0000-0000-000000000010";

    @Test
    @TestSecurity(user = "tam", roles = {"recruitment:read", "recruitment:write", "recruitment:admin"})
    void triggerRoleBrief_returns202_withArtifactUuid() {
        String roleUuid = seedRole();

        given()
                .header("X-Requested-By", TAM_UUID)
                .contentType("application/json").body("{}")
                .when().post("/api/recruitment/roles/" + roleUuid + "/ai/role-brief")
                .then().statusCode(202)
                .body("uuid", notNullValue())
                .body("state", oneOf("GENERATING", "GENERATED"));
    }

    @Test
    @TestSecurity(user = "noscope", roles = {})
    void triggerRoleBrief_withoutScope_returns403() {
        given()
                .contentType("application/json").body("{}")
                .when().post("/api/recruitment/roles/" + java.util.UUID.randomUUID() + "/ai/role-brief")
                .then().statusCode(403);
    }

    @Test
    @TestSecurity(user = "tam", roles = {"recruitment:read", "recruitment:write", "recruitment:admin"})
    void triggerRoleBrief_idempotent_returnsSameArtifactOnRepeat() {
        String roleUuid = seedRole();
        String first = triggerBrief(roleUuid);
        String second = triggerBrief(roleUuid);

        assertEquals(first, second,
                "same inputs → same artifact uuid (digest cache hit)");
    }

    /** Seed a minimal OpenRole via REST POST and return its UUID. */
    private String seedRole() {
        String body = """
            {
              "title": "Senior DEV consultant",
              "hiringCategory": "PRACTICE_CONSULTANT",
              "practice": "DEV",
              "teamUuid": "00000000-0000-0000-0000-000000000001",
              "hiringSource": "CAPACITY_GAP",
              "hiringReason": "Capacity gap"
            }""";
        return given()
                .header("X-Requested-By", TAM_UUID)
                .contentType("application/json").body(body)
                .when().post("/api/recruitment/roles")
                .then().statusCode(201)
                .extract().path("uuid");
    }

    /** POST the brief trigger and return the artifact UUID from the response body. */
    private String triggerBrief(String roleUuid) {
        return given()
                .header("X-Requested-By", TAM_UUID)
                .contentType("application/json").body("{}")
                .when().post("/api/recruitment/roles/" + roleUuid + "/ai/role-brief")
                .then().statusCode(202)
                .extract().path("uuid");
    }
}
