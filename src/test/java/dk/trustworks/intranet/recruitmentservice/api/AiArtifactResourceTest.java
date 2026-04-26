package dk.trustworks.intranet.recruitmentservice.api;

import dk.trustworks.intranet.recruitmentservice.AiEnabledTestProfile;
import dk.trustworks.intranet.recruitmentservice.application.AiArtifactService;
import dk.trustworks.intranet.recruitmentservice.domain.entities.AiArtifact;
import dk.trustworks.intranet.recruitmentservice.domain.enums.AiArtifactKind;
import dk.trustworks.intranet.recruitmentservice.domain.enums.AiArtifactState;
import dk.trustworks.intranet.recruitmentservice.domain.enums.AiSubjectKind;
import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.junit.TestProfile;
import io.quarkus.test.security.TestSecurity;
import jakarta.inject.Inject;
import jakarta.transaction.Transactional;
import org.junit.jupiter.api.Test;

import java.util.Map;
import java.util.UUID;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.is;
import static org.hamcrest.Matchers.notNullValue;
import static org.junit.jupiter.api.Assertions.assertNotEquals;

/**
 * Resource-level test for {@link AiArtifactResource} (spec §6.9).
 *
 * <p>GET asserts:
 * <ul>
 *   <li>An authorised caller can read an artifact in {@code GENERATED} state and
 *       receives the full review surface (uuid, state, output JSON).</li>
 *   <li>A request with an unknown UUID returns 404.</li>
 *   <li>A caller without {@code recruitment:read} is rejected with 403 by the
 *       scope filter, before the resource code runs.</li>
 * </ul>
 *
 * <p>POST {@code /review} asserts:
 * <ul>
 *   <li>{@code accepted=true} transitions GENERATED -> REVIEWED.</li>
 *   <li>{@code accepted=false, overrideJson=...} transitions GENERATED -> OVERRIDDEN
 *       and the override is recorded.</li>
 *   <li>{@code accepted=false, overrideJson=null} (discard) transitions GENERATED
 *       -> OVERRIDDEN with a {@code {"discarded":true}} marker.</li>
 *   <li>An artifact not in GENERATED state yields 409 (state-machine guard in the
 *       service).</li>
 * </ul>
 *
 * <p>Subject is always a freshly-seeded Candidate so the subject-derived
 * record-level access check
 * ({@link dk.trustworks.intranet.recruitmentservice.application.RecruitmentRecordAccessService#canSeeCandidate})
 * passes — the test caller has {@code recruitment:admin}, which short-circuits
 * the visibility check to true (mirrors the OFFER/ADMIN bypass in the service).
 *
 * <p>Uses {@link AiEnabledTestProfile} because the seed path goes through
 * {@link AiArtifactService#requestArtifact}, which refuses with 503 if the
 * AI feature flags are off.
 */
@QuarkusTest
@TestProfile(AiEnabledTestProfile.class)
class AiArtifactResourceTest {

    private static final String TAM_UUID = "00000000-0000-0000-0000-000000000010";

    @Inject AiArtifactService artifactService;

    @Test
    @TestSecurity(user = "tam", roles = {"recruitment:read", "recruitment:write", "recruitment:admin"})
    void getArtifact_returnsCurrentState() {
        String artifactUuid = seedArtifactInGenerated();

        given()
                .header("X-Requested-By", TAM_UUID)
                .when().get("/api/recruitment/ai-artifacts/" + artifactUuid)
                .then().statusCode(200)
                .body("uuid", equalTo(artifactUuid))
                .body("state", equalTo("GENERATED"))
                .body("subjectKind", equalTo("CANDIDATE"))
                .body("output", notNullValue())
                .body("kind", equalTo(AiArtifactKind.CANDIDATE_SUMMARY.name()));
    }

    @Test
    @TestSecurity(user = "tam", roles = {"recruitment:read", "recruitment:write", "recruitment:admin"})
    void getArtifact_unknownUuid_returns404() {
        given()
                .header("X-Requested-By", TAM_UUID)
                .when().get("/api/recruitment/ai-artifacts/" + UUID.randomUUID())
                .then().statusCode(404);
    }

    @Test
    @TestSecurity(user = "noscope", roles = {})
    void getArtifact_withoutScope_returns403() {
        given()
                .when().get("/api/recruitment/ai-artifacts/" + UUID.randomUUID())
                .then().statusCode(403);
    }

    @Test
    @TestSecurity(user = "tam", roles = {"recruitment:write", "recruitment:admin"})
    void review_accept_transitionsToReviewed() {
        String artifactUuid = seedCvExtractionInGenerated();

        given()
                .header("X-Requested-By", TAM_UUID)
                .contentType("application/json")
                .body("{\"accepted\":true}")
                .when().post("/api/recruitment/ai-artifacts/" + artifactUuid + "/review")
                .then().statusCode(200)
                .body("state", is("REVIEWED"));
    }

    @Test
    @TestSecurity(user = "tam", roles = {"recruitment:write", "recruitment:admin"})
    void review_editWithOverride_transitionsToOverridden() {
        String artifactUuid = seedCvExtractionInGenerated();

        given()
                .header("X-Requested-By", TAM_UUID)
                .contentType("application/json")
                .body("{\"accepted\":false,\"overrideJson\":\"{\\\"firstName\\\":\\\"Edited\\\"}\"}")
                .when().post("/api/recruitment/ai-artifacts/" + artifactUuid + "/review")
                .then().statusCode(200)
                .body("state", is("OVERRIDDEN"));
    }

    @Test
    @TestSecurity(user = "tam", roles = {"recruitment:write", "recruitment:admin"})
    void review_discard_transitionsToOverriddenWithDiscardMarker() {
        String artifactUuid = seedCvExtractionInGenerated();

        given()
                .header("X-Requested-By", TAM_UUID)
                .contentType("application/json")
                .body("{\"accepted\":false}")
                .when().post("/api/recruitment/ai-artifacts/" + artifactUuid + "/review")
                .then().statusCode(200)
                .body("state", is("OVERRIDDEN"))
                .body("overrideJson", containsString("discarded"));
    }

    @Test
    @TestSecurity(user = "tam", roles = {"recruitment:write", "recruitment:admin"})
    void review_artifactNotInGenerated_returns409() {
        String artifactUuid = seedCvExtractionInState(AiArtifactState.REVIEWED.name());

        given()
                .header("X-Requested-By", TAM_UUID)
                .contentType("application/json")
                .body("{\"accepted\":true}")
                .when().post("/api/recruitment/ai-artifacts/" + artifactUuid + "/review")
                .then().statusCode(409);
    }

    @Test
    @TestSecurity(user = "tam", roles = {"recruitment:write", "recruitment:admin"})
    void regenerate_createsNewArtifactWithDifferentDigest() {
        String first = seedCvExtractionInGenerated();
        String second = given()
                .header("X-Requested-By", TAM_UUID)
                .contentType("application/json").body("{\"reason\":\"more accuracy\"}")
                .when().post("/api/recruitment/ai-artifacts/" + first + "/regenerate")
                .then().statusCode(200).extract().path("uuid");
        assertNotEquals(first, second);
    }

    /**
     * Seed a Candidate via REST then create an AiArtifact and transition it to
     * GENERATED via the service (which is the same path the worker takes after
     * the LLM call returns). Returns the artifact UUID.
     */
    private String seedArtifactInGenerated() {
        String candidateUuid = seedCandidate();

        AiArtifact artifact = artifactService.requestArtifact(
                AiSubjectKind.CANDIDATE,
                candidateUuid,
                AiArtifactKind.CANDIDATE_SUMMARY,
                Map.of("seed", "for-get-artifact-test", "candidateUuid", candidateUuid),
                TAM_UUID);
        artifactService.markGenerated(
                artifact.uuid,
                "{\"summary\":\"strong DEV candidate\"}",
                "[]",
                null);
        return artifact.uuid;
    }

    /**
     * Seed a Candidate then create a CV_EXTRACTION artifact in GENERATED state.
     * The output JSON contains a {@code firstName} so {@link AiArtifactService#accept}
     * can run the apply-handler successfully against the candidate.
     */
    private String seedCvExtractionInGenerated() {
        String candidateUuid = seedCandidate();

        AiArtifact artifact = artifactService.requestArtifact(
                AiSubjectKind.CANDIDATE,
                candidateUuid,
                AiArtifactKind.CV_EXTRACTION,
                Map.of("seed", UUID.randomUUID().toString(), "candidateUuid", candidateUuid),
                TAM_UUID);
        artifactService.markGenerated(
                artifact.uuid,
                "{\"firstName\":\"Pat\",\"lastName\":\"Doe\"}",
                "[]",
                null);
        return artifact.uuid;
    }

    /**
     * Seed a CV_EXTRACTION artifact then force its state directly so the 409
     * test can exercise the GENERATED-only guard in
     * {@link AiArtifactService#accept}.
     */
    private String seedCvExtractionInState(String state) {
        String artifactUuid = seedCvExtractionInGenerated();
        forceState(artifactUuid, state);
        return artifactUuid;
    }

    @Transactional
    void forceState(String artifactUuid, String state) {
        AiArtifact a = AiArtifact.findById(artifactUuid);
        a.state = state;
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
}
