package dk.trustworks.intranet.recruitmentservice.api;

import dk.trustworks.intranet.recruitmentservice.AiEnabledTestProfile;
import dk.trustworks.intranet.recruitmentservice.ports.CvToolPort;
import io.quarkus.test.InjectMock;
import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.junit.TestProfile;
import io.quarkus.test.security.TestSecurity;
import org.junit.jupiter.api.Test;

import java.util.List;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.notNullValue;
import static org.hamcrest.Matchers.oneOf;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.when;

/**
 * Resource-level test for {@link CandidateAiResource} (spec §6.3).
 *
 * <p>Uses {@link AiEnabledTestProfile} so {@code recruitment.ai.enabled} and
 * {@code recruitment.ai.candidate-summary.enabled} are both true; otherwise
 * {@link dk.trustworks.intranet.recruitmentservice.application.AiArtifactService#requestArtifact}
 * refuses with 503 Service Unavailable.
 *
 * <p>{@link CvToolPort#findByPractice} is mocked so the test stays hermetic — the live
 * implementation reads from the {@code cv_tool_employee_cv} table which is outside this
 * slice's seed surface. The empty list returned still exercises the resource's "no
 * comparables" branch end-to-end.
 *
 * <p>Three asserts cover the spec contract:
 * <ul>
 *   <li>Authorised callers receive 202 Accepted plus the persisted artifact UUID.</li>
 *   <li>Callers without {@code recruitment:read} are rejected with 403.</li>
 *   <li>Two consecutive triggers for the same candidate yield the <em>same</em> artifact
 *       UUID (idempotency via the {@code input_digest} unique key in
 *       {@code recruitment_ai_artifact}).</li>
 * </ul>
 *
 * <p>Note on scope: spec §6.3 deliberately scopes this endpoint to {@code recruitment:read}
 * (not write) because the candidate summary is informational — accepting it does not
 * mutate the Candidate aggregate. The 403 test uses an empty role set to verify the
 * scope filter rejects unscoped callers.
 */
@QuarkusTest
@TestProfile(AiEnabledTestProfile.class)
class CandidateAiResourceTest {

    private static final String TAM_UUID = "00000000-0000-0000-0000-000000000010";

    @InjectMock CvToolPort cvTool;

    @Test
    @TestSecurity(user = "tam", roles = {"recruitment:read", "recruitment:write", "recruitment:admin"})
    void triggerSummary_returns202_withArtifactUuid() {
        when(cvTool.findByPractice(anyString(), anyInt())).thenReturn(List.of());
        String candidateUuid = seedCandidate();

        given()
                .header("X-Requested-By", TAM_UUID)
                .contentType("application/json").body("{}")
                .when().post("/api/recruitment/candidates/" + candidateUuid + "/ai/summary")
                .then().statusCode(202)
                .body("uuid", notNullValue())
                .body("state", oneOf("GENERATING", "GENERATED"));
    }

    @Test
    @TestSecurity(user = "noscope", roles = {})
    void triggerSummary_withoutScope_returns403() {
        given()
                .contentType("application/json").body("{}")
                .when().post("/api/recruitment/candidates/" + java.util.UUID.randomUUID() + "/ai/summary")
                .then().statusCode(403);
    }

    @Test
    @TestSecurity(user = "tam", roles = {"recruitment:read", "recruitment:write", "recruitment:admin"})
    void triggerSummary_idempotent_returnsSameArtifactOnRepeat() {
        when(cvTool.findByPractice(anyString(), anyInt())).thenReturn(List.of());
        String candidateUuid = seedCandidate();
        String first = triggerSummary(candidateUuid);
        String second = triggerSummary(candidateUuid);

        assertEquals(first, second,
                "same inputs → same artifact uuid (digest cache hit)");
    }

    /** Seed a minimal DEV-practice Candidate via REST POST and return its UUID. */
    private String seedCandidate() {
        String body = """
            {"firstName":"Pat","lastName":"Doe","email":"pat@example.com","desiredPractice":"DEV"}""";
        return given()
                .header("X-Requested-By", TAM_UUID)
                .contentType("application/json").body(body)
                .when().post("/api/recruitment/candidates")
                .then().statusCode(201)
                .extract().path("uuid");
    }

    /** POST the summary trigger and return the artifact UUID from the response body. */
    private String triggerSummary(String candidateUuid) {
        return given()
                .header("X-Requested-By", TAM_UUID)
                .contentType("application/json").body("{}")
                .when().post("/api/recruitment/candidates/" + candidateUuid + "/ai/summary")
                .then().statusCode(202)
                .extract().path("uuid");
    }
}
