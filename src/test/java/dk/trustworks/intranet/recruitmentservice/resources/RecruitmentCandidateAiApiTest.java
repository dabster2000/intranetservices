package dk.trustworks.intranet.recruitmentservice.resources;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import dk.trustworks.intranet.apis.openai.OpenAIService;
import io.quarkus.narayana.jta.QuarkusTransaction;
import io.quarkus.test.InjectMock;
import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.security.TestSecurity;
import jakarta.inject.Inject;
import jakarta.persistence.EntityManager;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.UUID;

import static dk.trustworks.intranet.recruitmentservice.events.RecruitmentEventPiiAssertions.PII_SENTINEL;
import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.hasItems;
import static org.hamcrest.Matchers.hasSize;
import static org.hamcrest.Matchers.notNullValue;
import static org.hamcrest.Matchers.nullValue;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.when;

/**
 * P9 §8.8: the candidate AI endpoints end-to-end — state derivation
 * (resolution + supersession + populated-field filtering), the resolve
 * command's apply/dismiss/conflict matrix, the regenerate guardrails
 * (rate limit, NO_OPEN_APPLICATION), the visibility 404 and the
 * booleans-only flags endpoint.
 */
@QuarkusTest
class RecruitmentCandidateAiApiTest {

    private static final String INTAKE_FLAG = "recruitment.ai.intake.enabled";
    private static final String BRIEF_FLAG = "recruitment.ai.brief.enabled";

    private static final ObjectMapper MAPPER = new ObjectMapper();

    @Inject
    EntityManager em;

    @InjectMock
    OpenAIService openAIService;

    private String practiceUuid;
    private String hrUser;
    private String plainUser;
    private String positionUuid;
    private String candidateUuid;
    private String applicationUuid;

    private String previousPipeline;
    private String previousIntake;
    private String previousBrief;

    @BeforeEach
    void seed() {
        practiceUuid = UUID.randomUUID().toString();
        hrUser = UUID.randomUUID().toString();
        plainUser = UUID.randomUUID().toString();
        positionUuid = UUID.randomUUID().toString();
        candidateUuid = UUID.randomUUID().toString();
        applicationUuid = UUID.randomUUID().toString();

        QuarkusTransaction.requiringNew().run(() -> {
            P8ProfileFixtures.insertUser(em, hrUser, "Rina", "Recruiter");
            P8ProfileFixtures.insertRole(em, hrUser, "HR");
            P8ProfileFixtures.insertUser(em, plainUser, "Palle", "Plain");
            P8ProfileFixtures.insertPractice(em, practiceUuid);
            P8ProfileFixtures.insertPosition(em, positionUuid, "Consultant",
                    "PRACTICE_TEAM", practiceUuid, null, null);
            P8ProfileFixtures.insertCandidate(em, candidateUuid,
                    PII_SENTINEL + " Anna", PII_SENTINEL + " Ager", "ACTIVE", null, null, hrUser);
            P8ProfileFixtures.insertOpenApplication(em, applicationUuid,
                    candidateUuid, positionUuid, "SCREENING");
            previousPipeline = P8ProfileFixtures.setFlag(em, P8ProfileFixtures.PIPELINE_FLAG, "true");
            previousIntake = P8ProfileFixtures.setFlag(em, INTAKE_FLAG, "true");
            previousBrief = P8ProfileFixtures.setFlag(em, BRIEF_FLAG, "true");
        });
        when(openAIService.getDefaultModel()).thenReturn("test-model");
        when(openAIService.getVisionModel()).thenReturn("test-vision");
    }

    @AfterEach
    void cleanup() {
        QuarkusTransaction.requiringNew().run(() -> {
            P8ProfileFixtures.cleanupRecruitmentRows(em,
                    List.of(candidateUuid), List.of(positionUuid),
                    List.of(hrUser, plainUser), practiceUuid);
            P8ProfileFixtures.restoreFlag(em, P8ProfileFixtures.PIPELINE_FLAG, previousPipeline);
            P8ProfileFixtures.restoreFlag(em, INTAKE_FLAG, previousIntake);
            P8ProfileFixtures.restoreFlag(em, BRIEF_FLAG, previousBrief);
        });
    }

    // ---- fixtures ----------------------------------------------------------------

    /** One intake generation with EDUCATION_LEVEL, LANGUAGES and EXPERIENCE_LEVEL. */
    private void seedGeneration(String generationId) {
        String payload = """
                {"generation_id":"%s","origin":"reactor","model":"test-model",
                 "prompt_version":"intake-v1",
                 "fields":["EDUCATION_LEVEL","EXPERIENCE_LEVEL","LANGUAGES"]}
                """.formatted(generationId);
        String pii = """
                {"suggestions":[
                  {"id":"%1$s:EDUCATION_LEVEL","field":"EDUCATION_LEVEL","value":"MASTER",
                   "evidence":"%2$s cand.merc. naevnt"},
                  {"id":"%1$s:EXPERIENCE_LEVEL","field":"EXPERIENCE_LEVEL","value":"SENIOR",
                   "evidence":"%2$s otte aars erfaring"},
                  {"id":"%1$s:LANGUAGES","field":"LANGUAGES","value":["Dansk","Engelsk"],
                   "evidence":"%2$s sprogsektion"}]}
                """.formatted(generationId, PII_SENTINEL);
        QuarkusTransaction.requiringNew().run(() ->
                P8ProfileFixtures.insertEvent(em, "AI_SUGGESTIONS_GENERATED", candidateUuid,
                        applicationUuid, positionUuid, "SYSTEM", null, "NORMAL", payload, pii));
    }

    private void seedBrief(String generationId) {
        QuarkusTransaction.requiringNew().run(() ->
                P8ProfileFixtures.insertEvent(em, "AI_BRIEF_GENERATED", candidateUuid,
                        applicationUuid, positionUuid, "SYSTEM", null, "NORMAL",
                        "{\"generation_id\":\"" + generationId + "\",\"origin\":\"reactor\","
                                + "\"model\":\"test-model\",\"prompt_version\":\"brief-v1\"}",
                        "{\"bullets\":[\"" + PII_SENTINEL + " punkt et\",\"punkt to\",\"punkt tre\"]}"));
    }

    private void resolveOk(String suggestionId, boolean accepted) {
        given().header("X-Requested-By", hrUser)
                .contentType("application/json")
                .body(Map.of("suggestionId", suggestionId, "accepted", accepted))
                .when().post("/recruitment/candidates/{uuid}/ai/suggestions/resolve", candidateUuid)
                .then().statusCode(200);
    }

    private List<JsonNode> events(String type) {
        return QuarkusTransaction.requiringNew().call(() -> {
            em.clear();
            List<Object[]> rows = em.createNativeQuery(
                            "SELECT payload, actor_type, actor_uuid, pii FROM recruitment_events "
                                    + "WHERE candidate_uuid = :c AND event_type = :t ORDER BY seq")
                    .setParameter("c", candidateUuid).setParameter("t", type).getResultList();
            return rows.stream().map(r -> {
                try {
                    var node = MAPPER.createObjectNode();
                    node.set("payload", r[0] == null ? null : MAPPER.readTree((String) r[0]));
                    node.put("actorType", (String) r[1]);
                    node.put("actorUuid", (String) r[2]);
                    node.set("pii", r[3] == null ? null : MAPPER.readTree((String) r[3]));
                    return (JsonNode) node;
                } catch (Exception e) {
                    throw new IllegalStateException(e);
                }
            }).toList();
        });
    }

    private String candidateColumn(String column) {
        return QuarkusTransaction.requiringNew().call(() -> {
            em.clear();
            Object value = em.createNativeQuery("SELECT " + column
                            + " FROM recruitment_candidates WHERE uuid = :u")
                    .setParameter("u", candidateUuid).getSingleResult();
            return value == null ? null : value.toString();
        });
    }

    // ---- state derivation ---------------------------------------------------------

    @Test
    @TestSecurity(user = "bff-client", roles = {"recruitment:read"})
    void state_returnsBriefAndUnresolvedSuggestions() {
        seedGeneration("gen1");
        seedBrief("gen1");

        given().header("X-Requested-By", hrUser)
                .when().get("/recruitment/candidates/{uuid}/ai/state", candidateUuid)
                .then().statusCode(200)
                .body("brief.bullets", hasSize(3))
                .body("brief.model", equalTo("test-model"))
                .body("brief.generatedAt", notNullValue())
                .body("suggestions", hasSize(3))
                .body("suggestions.field", hasItems("EDUCATION_LEVEL", "EXPERIENCE_LEVEL", "LANGUAGES"))
                .body("suggestions[0].generationId", equalTo("gen1"))
                .body("regenerate.remainingToday", equalTo(5))
                .body("regenerate.hasOpenApplication", equalTo(true));
    }

    @Test
    @TestSecurity(user = "bff-client", roles = {"recruitment:read"})
    void state_flagsOff_hidesBriefAndSuggestions() {
        seedGeneration("gen1");
        seedBrief("gen1");
        QuarkusTransaction.requiringNew().run(() -> {
            P8ProfileFixtures.setFlag(em, INTAKE_FLAG, "false");
            P8ProfileFixtures.setFlag(em, BRIEF_FLAG, "false");
        });

        given().header("X-Requested-By", hrUser)
                .when().get("/recruitment/candidates/{uuid}/ai/state", candidateUuid)
                .then().statusCode(200)
                .body("brief", nullValue())
                .body("suggestions", hasSize(0));
    }

    @Test
    @TestSecurity(user = "bff-client", roles = {"recruitment:read"})
    void state_populatedField_filtersThatSuggestion() {
        seedGeneration("gen1");
        QuarkusTransaction.requiringNew().run(() -> em.createNativeQuery(
                        "UPDATE recruitment_candidates SET experience_level = 'MID' WHERE uuid = :u")
                .setParameter("u", candidateUuid).executeUpdate());

        given().header("X-Requested-By", hrUser)
                .when().get("/recruitment/candidates/{uuid}/ai/state", candidateUuid)
                .then().statusCode(200)
                .body("suggestions", hasSize(2))
                .body("suggestions.field", hasItems("EDUCATION_LEVEL", "LANGUAGES"));
    }

    @Test
    @TestSecurity(user = "bff-client", roles = {"recruitment:read"})
    void state_newGenerationSupersedesOlder() {
        seedGeneration("gen1");
        seedGeneration("gen2");

        given().header("X-Requested-By", hrUser)
                .when().get("/recruitment/candidates/{uuid}/ai/state", candidateUuid)
                .then().statusCode(200)
                .body("suggestions", hasSize(3))
                .body("suggestions[0].generationId", equalTo("gen2"));
    }

    // ---- resolve ------------------------------------------------------------------

    @Test
    @TestSecurity(user = "bff-client", roles = {"recruitment:read", "recruitment:write"})
    void resolveAccept_appliesValue_emitsCandidateUpdatedWithUserActor_thenResolved() throws Exception {
        seedGeneration("gen1");

        given().header("X-Requested-By", hrUser)
                .contentType("application/json")
                .body(Map.of("suggestionId", "gen1:EDUCATION_LEVEL", "accepted", true))
                .when().post("/recruitment/candidates/{uuid}/ai/suggestions/resolve", candidateUuid)
                .then().statusCode(200)
                .body("suggestions", hasSize(2)); // education gone: resolved AND now populated

        assertEquals("MASTER", candidateColumn("education_level"),
                "the accepted value must be applied server-side");

        List<JsonNode> updated = events("CANDIDATE_UPDATED");
        assertEquals(1, updated.size());
        assertEquals("USER", updated.get(0).path("actorType").asText(),
                "the applying CANDIDATE_UPDATED carries the USER as actor");
        assertEquals(hrUser, updated.get(0).path("actorUuid").asText());

        List<JsonNode> resolved = events("AI_SUGGESTION_RESOLVED");
        assertEquals(1, resolved.size());
        JsonNode payload = resolved.get(0).path("payload");
        assertEquals("gen1:EDUCATION_LEVEL", payload.path("suggestion_id").asText());
        assertEquals("gen1", payload.path("generation_id").asText());
        assertEquals("EDUCATION_LEVEL", payload.path("field").asText());
        assertTrue(payload.path("accepted").asBoolean());
        assertTrue(resolved.get(0).path("pii").isNull() || resolved.get(0).path("pii").isMissingNode(),
                "AI_SUGGESTION_RESOLVED carries no pii");
    }

    @Test
    @TestSecurity(user = "bff-client", roles = {"recruitment:read", "recruitment:write"})
    void resolveAcceptListField_unionsCurrentAndSuggested() {
        seedGeneration("gen1");
        QuarkusTransaction.requiringNew().run(() -> em.createNativeQuery(
                        "UPDATE recruitment_candidates SET languages = NULL WHERE uuid = :u")
                .setParameter("u", candidateUuid).executeUpdate());

        resolveOk("gen1:LANGUAGES", true);

        String languages = candidateColumn("languages");
        assertTrue(languages.contains("Dansk") && languages.contains("Engelsk"),
                "list acceptance stores the union (here: the suggested set)");
    }

    @Test
    @TestSecurity(user = "bff-client", roles = {"recruitment:read", "recruitment:write"})
    void resolveDismiss_onlyRecordsResolution() {
        seedGeneration("gen1");

        given().header("X-Requested-By", hrUser)
                .contentType("application/json")
                .body(Map.of("suggestionId", "gen1:EXPERIENCE_LEVEL", "accepted", false))
                .when().post("/recruitment/candidates/{uuid}/ai/suggestions/resolve", candidateUuid)
                .then().statusCode(200)
                .body("suggestions", hasSize(2));

        assertEquals(null, candidateColumn("experience_level"), "dismiss must not touch the field");
        assertEquals(0, events("CANDIDATE_UPDATED").size());
        assertEquals(1, events("AI_SUGGESTION_RESOLVED").size());
    }

    @Test
    @TestSecurity(user = "bff-client", roles = {"recruitment:read", "recruitment:write"})
    void resolveTwice_secondIsNoop200_noDuplicateEvents() {
        seedGeneration("gen1");

        resolveOk("gen1:EXPERIENCE_LEVEL", false);
        resolveOk("gen1:EXPERIENCE_LEVEL", false);

        assertEquals(1, events("AI_SUGGESTION_RESOLVED").size(),
                "re-resolving must be double-click safe");
    }

    @Test
    @TestSecurity(user = "bff-client", roles = {"recruitment:read", "recruitment:write"})
    void resolveUnknownOrSupersededId_is409Stale() {
        seedGeneration("gen1");

        given().header("X-Requested-By", hrUser)
                .contentType("application/json")
                .body(Map.of("suggestionId", "nope:EDUCATION_LEVEL", "accepted", true))
                .when().post("/recruitment/candidates/{uuid}/ai/suggestions/resolve", candidateUuid)
                .then().statusCode(409)
                .body("error", equalTo("STALE_SUGGESTION"));

        seedGeneration("gen2"); // supersedes gen1
        given().header("X-Requested-By", hrUser)
                .contentType("application/json")
                .body(Map.of("suggestionId", "gen1:EDUCATION_LEVEL", "accepted", true))
                .when().post("/recruitment/candidates/{uuid}/ai/suggestions/resolve", candidateUuid)
                .then().statusCode(409)
                .body("error", equalTo("STALE_SUGGESTION"));
    }

    @Test
    @TestSecurity(user = "bff-client", roles = {"recruitment:read", "recruitment:write"})
    void resolveAcceptOnPopulatedField_is409FieldAlreadySet() {
        seedGeneration("gen1");
        QuarkusTransaction.requiringNew().run(() -> em.createNativeQuery(
                        "UPDATE recruitment_candidates SET education_level = 'PHD' WHERE uuid = :u")
                .setParameter("u", candidateUuid).executeUpdate());

        given().header("X-Requested-By", hrUser)
                .contentType("application/json")
                .body(Map.of("suggestionId", "gen1:EDUCATION_LEVEL", "accepted", true))
                .when().post("/recruitment/candidates/{uuid}/ai/suggestions/resolve", candidateUuid)
                .then().statusCode(409)
                .body("error", equalTo("FIELD_ALREADY_SET"));

        assertEquals("PHD", candidateColumn("education_level"), "the race guard must not overwrite");
    }

    @Test
    @TestSecurity(user = "bff-client", roles = {"recruitment:read", "recruitment:write"})
    void resolveMissingBodyFields_are400() {
        seedGeneration("gen1");
        given().header("X-Requested-By", hrUser)
                .contentType("application/json")
                .body(Map.of("accepted", true))
                .when().post("/recruitment/candidates/{uuid}/ai/suggestions/resolve", candidateUuid)
                .then().statusCode(400);
        given().header("X-Requested-By", hrUser)
                .contentType("application/json")
                .body(Map.of("suggestionId", "gen1:EDUCATION_LEVEL"))
                .when().post("/recruitment/candidates/{uuid}/ai/suggestions/resolve", candidateUuid)
                .then().statusCode(400);
    }

    // ---- regenerate ---------------------------------------------------------------

    @Test
    @TestSecurity(user = "bff-client", roles = {"recruitment:read", "recruitment:write"})
    void regenerate_runsPipelineSynchronously_originRegenerate_noSourceSeq() throws Exception {
        when(openAIService.askQuestionWithSchema(anyString(), anyString(), any(), any(),
                any(), any(), anyInt(), anyBoolean())).thenReturn("""
                {"suggestions":{
                  "educationLevel":"MASTER","educationLevelEvidence":"cand.merc.",
                  "experienceLevel":null,"experienceLevelEvidence":null,
                  "specializations":null,"specializationsEvidence":null,
                  "languages":null,"languagesEvidence":null,
                  "currentEmployer":null,"currentEmployerEvidence":null},
                 "brief":["Punkt et","Punkt to","Punkt tre"]}
                """);

        given().header("X-Requested-By", hrUser)
                .when().post("/recruitment/candidates/{uuid}/ai/regenerate", candidateUuid)
                .then().statusCode(200)
                .body("suggestions", hasSize(1))
                .body("brief.bullets", hasSize(3))
                .body("regenerate.remainingToday", equalTo(4));

        List<JsonNode> generations = events("AI_SUGGESTIONS_GENERATED");
        assertEquals(1, generations.size());
        JsonNode payload = generations.get(0).path("payload");
        assertEquals("regenerate", payload.path("origin").asText());
        assertTrue(payload.path("source_event_seq").isMissingNode(),
                "regenerate has no triggering event — the key is omitted");
    }

    @Test
    @TestSecurity(user = "bff-client", roles = {"recruitment:read", "recruitment:write"})
    void regenerate_sixthCallToday_is429RateLimited() {
        for (int i = 1; i <= 5; i++) {
            String generationId = "regen-" + i;
            QuarkusTransaction.requiringNew().run(() ->
                    P8ProfileFixtures.insertEvent(em, "AI_BRIEF_GENERATED", candidateUuid,
                            applicationUuid, positionUuid, "SYSTEM", null, "NORMAL",
                            "{\"generation_id\":\"" + generationId
                                    + "\",\"origin\":\"regenerate\",\"model\":\"m\","
                                    + "\"prompt_version\":\"brief-v1\"}",
                            "{\"bullets\":[\"x\"]}"));
        }

        given().header("X-Requested-By", hrUser)
                .when().get("/recruitment/candidates/{uuid}/ai/state", candidateUuid)
                .then().statusCode(200)
                .body("regenerate.remainingToday", equalTo(0));

        given().header("X-Requested-By", hrUser)
                .when().post("/recruitment/candidates/{uuid}/ai/regenerate", candidateUuid)
                .then().statusCode(429)
                .body("error", equalTo("RATE_LIMITED"));
    }

    @Test
    @TestSecurity(user = "bff-client", roles = {"recruitment:read", "recruitment:write"})
    void regenerate_withoutOpenApplication_is400() {
        QuarkusTransaction.requiringNew().run(() -> em.createNativeQuery(
                        "UPDATE recruitment_applications SET terminal = 'REJECTED' WHERE uuid = :u")
                .setParameter("u", applicationUuid).executeUpdate());

        given().header("X-Requested-By", hrUser)
                .when().post("/recruitment/candidates/{uuid}/ai/regenerate", candidateUuid)
                .then().statusCode(400)
                .body("error", equalTo("NO_OPEN_APPLICATION"));
    }

    // ---- authz --------------------------------------------------------------------

    @Test
    @TestSecurity(user = "bff-client", roles = {"recruitment:read", "recruitment:write"})
    void invisibleCandidate_answers404OnEveryAiSurface() {
        seedGeneration("gen1");
        given().header("X-Requested-By", plainUser)
                .when().get("/recruitment/candidates/{uuid}/ai/state", candidateUuid)
                .then().statusCode(404);
        given().header("X-Requested-By", plainUser)
                .when().post("/recruitment/candidates/{uuid}/ai/regenerate", candidateUuid)
                .then().statusCode(404);
        given().header("X-Requested-By", plainUser)
                .contentType("application/json")
                .body(Map.of("suggestionId", "gen1:EDUCATION_LEVEL", "accepted", true))
                .when().post("/recruitment/candidates/{uuid}/ai/suggestions/resolve", candidateUuid)
                .then().statusCode(404);
    }

    // ---- flags endpoint -----------------------------------------------------------

    @Test
    @TestSecurity(user = "bff-client", roles = {"recruitment:read"})
    void flagsEndpoint_reportsLiteralBooleans() {
        QuarkusTransaction.requiringNew().run(() -> {
            P8ProfileFixtures.setFlag(em, INTAKE_FLAG, "true");
            P8ProfileFixtures.setFlag(em, BRIEF_FLAG, "false");
        });

        given().header("X-Requested-By", hrUser)
                .when().get("/recruitment/ai/flags")
                .then().statusCode(200)
                .body("intake", equalTo(true))
                .body("brief", equalTo(false))
                .body("referralTriage", equalTo(false))
                .body("emailComposer", equalTo(false))
                .body("weeklyFunnelDigest", equalTo(false))
                .body("rejectionPatternsDigest", equalTo(false));
    }
}
