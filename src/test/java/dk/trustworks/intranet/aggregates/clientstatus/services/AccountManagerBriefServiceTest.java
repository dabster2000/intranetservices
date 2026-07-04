package dk.trustworks.intranet.aggregates.clientstatus.services;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import dk.trustworks.intranet.aggregates.clientstatus.services.AccountManagerBriefService.*;
import dk.trustworks.intranet.apis.openai.OpenAIService;
import io.quarkus.test.InjectMock;
import io.quarkus.test.TestTransaction;
import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.security.TestSecurity;
import jakarta.inject.Inject;
import jakarta.persistence.EntityManager;
import jakarta.ws.rs.core.MediaType;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.UUID;

import static io.restassured.RestAssured.given;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.when;

/**
 * Pure unit tests for the DB-free payload/label/sanitize helpers of the AM brief, plus
 * endpoint tests (with a mocked {@link OpenAIService}) covering the happy path, request
 * validation, and the blank-response &rarr; 502 branch (spec B4).
 */
@QuarkusTest
class AccountManagerBriefServiceTest {

    private static final String AM_BRIEF_PATH = "/invoice-controlling/client-status/account-manager-brief";

    @Inject
    EntityManager em;

    @InjectMock
    OpenAIService openAIService;

    // --- Pure helper tests (no DB, no HTTP) ---

    @Test
    void monthLabel_danishLongForm() {
        assertEquals("januar 2026", AccountManagerBriefService.monthLabel("202601"));
        assertEquals("juni 2026", AccountManagerBriefService.monthLabel("202606"));
    }

    @Test
    void sanitize_stripsHtmlControlCharsAndCaps() {
        assertEquals("Banedanmark", AccountManagerBriefService.sanitize("<b>Banedanmark</b>"));
        assertEquals("a b", AccountManagerBriefService.sanitize("a\nb"));
        assertEquals("", AccountManagerBriefService.sanitize(null));
        assertTrue(AccountManagerBriefService.sanitize("x".repeat(500)).length() <= 120);
    }

    @Test
    void buildSchema_hasStrictSlackTextShape() {
        ObjectNode schema = AccountManagerBriefService.buildSchema();
        assertEquals("object", schema.get("type").asText());
        assertFalse(schema.get("additionalProperties").asBoolean());
        assertTrue(schema.path("properties").path("slackText").path("type").asText().equals("string"));
        assertEquals("slackText", schema.path("required").get(0).asText());
    }

    @Test
    void buildPayload_includesMonthSeriesConsultantsProjectsStatsAndExcluded() {
        var missingConsultant = new ConsultantGap("Sara Vest", 316_000, 0, 316_000);
        var shiftedConsultant = new ConsultantGap("Daugaard", 0, 120_000, -120_000);
        var project = new ProjectGap("Project Management", 185_119);
        var gapMonth = new MonthAnalysis("januar 2026", 1_856_127, 1_135_147, -720_980, true,
                List.of(missingConsultant), List.of(project), 5_000);
        var fullMonth = new MonthAnalysis("februar 2026", 900_000, 1_020_000, 120_000, false,
                List.of(shiftedConsultant), List.of(), 0);
        var client = new ClientAnalysis("Banedanmark", List.of(gapMonth, fullMonth), 1, 720_980);

        ObjectNode payload = AccountManagerBriefService.buildPayload(
                "Tommy", Framing.TO_AM, List.of(client), List.of("VATTENFALL VINDKRAFT A/S"), 42);

        assertEquals("Tommy", payload.get("accountManager").asText());
        assertEquals("TO_AM", payload.get("framing").asText());
        assertEquals(42, payload.get("variationSeed").asInt());
        assertEquals(1, payload.path("stats").get("clientsWithGaps").asInt());
        assertEquals(1, payload.path("stats").get("gapMonths").asInt());
        assertEquals(720_980, payload.path("stats").get("totalMissingDkk").asLong());

        JsonNode c0 = payload.get("clients").get(0);
        assertEquals("Banedanmark", c0.get("name").asText());
        JsonNode m0 = c0.get("months").get(0);
        assertEquals("januar 2026", m0.get("month").asText());
        assertEquals(-720_980, m0.get("delta").asLong());
        assertTrue(m0.get("gap").asBoolean());
        assertEquals("Sara Vest", m0.get("consultants").get(0).get("name").asText());
        assertEquals(185_119, m0.get("projectGaps").get(0).get("missing").asLong());
        assertEquals(5_000, m0.get("unmatchedInvoiced").asLong());

        // Non-gap month rides along with the timing-shift counterpart consultant.
        JsonNode m1 = c0.get("months").get(1);
        assertFalse(m1.get("gap").asBoolean());
        assertEquals(-120_000, m1.get("consultants").get(0).get("missing").asLong());
        assertNull(m1.get("projectGaps"), "non-gap months carry no project breakdown");
        assertNull(m1.get("unmatchedInvoiced"));

        assertEquals("VATTENFALL VINDKRAFT A/S", payload.get("excludedSelfBilled").get(0).asText());
    }

    @Test
    void buildPayload_selfFraming_encodedInPayload() {
        ObjectNode payload = AccountManagerBriefService.buildPayload(
                "Tommy", Framing.SELF, List.of(), List.of(), 7);
        assertEquals("SELF", payload.get("framing").asText());
        assertTrue(payload.get("clients").isEmpty());
        assertTrue(payload.get("excludedSelfBilled").isEmpty());
        assertEquals(0, payload.path("stats").get("gapMonths").asInt());
    }

    @Test
    void buildPayload_sanitizesAccountManagerName() {
        ObjectNode payload = AccountManagerBriefService.buildPayload(
                "<script>Tommy</script>", Framing.TO_AM, List.of(), List.of(), 7);
        assertEquals("Tommy", payload.get("accountManager").asText(),
                "AM firstname must be sanitized before entering the prompt payload");
    }

    // --- Endpoint tests (mocked OpenAIService) ---

    @Test
    @TestTransaction
    @TestSecurity(user = "controller", roles = {"invoices:read"})
    void endpoint_happyPath_returnsSlackTextFromModel() {
        String amUuid = seedUser("Tommy", "Sørensen");
        when(openAIService.askQuestionWithSchema(
                anyString(), anyString(), any(), anyString(), anyString(), anyString(), anyInt()))
                .thenReturn("{\"slackText\":\"Hej Tommy.\"}");

        given()
                .header("X-Requested-By", "controller")
                .contentType(MediaType.APPLICATION_JSON)
                .body("{\"accountManagerUuid\":\"" + amUuid + "\"}")
                .when()
                .post(AM_BRIEF_PATH)
                .then()
                .statusCode(200)
                .body("slackText", org.hamcrest.Matchers.is("Hej Tommy."))
                .body("accountManagerUuid", org.hamcrest.Matchers.is(amUuid))
                .body("accountManagerName", org.hamcrest.Matchers.is("Tommy Sørensen"))
                .body("model", org.hamcrest.Matchers.notNullValue());
    }

    @Test
    @TestTransaction
    @TestSecurity(user = "controller", roles = {"invoices:read"})
    void endpoint_blankModelResponse_returns502() {
        String amUuid = seedUser("Tommy", "Sørensen");
        when(openAIService.askQuestionWithSchema(
                anyString(), anyString(), any(), anyString(), anyString(), anyString(), anyInt()))
                .thenReturn("{\"slackText\":\"\"}");

        given()
                .header("X-Requested-By", "controller")
                .contentType(MediaType.APPLICATION_JSON)
                .body("{\"accountManagerUuid\":\"" + amUuid + "\"}")
                .when()
                .post(AM_BRIEF_PATH)
                .then()
                .statusCode(502);
    }

    @Test
    @TestSecurity(user = "controller", roles = {"invoices:read"})
    void endpoint_invalidAccountManagerUuid_returns400() {
        given()
                .header("X-Requested-By", "controller")
                .contentType(MediaType.APPLICATION_JSON)
                .body("{\"accountManagerUuid\":\"not-a-uuid\"}")
                .when()
                .post(AM_BRIEF_PATH)
                .then()
                .statusCode(400);
    }

    @Test
    @TestTransaction
    @TestSecurity(user = "controller", roles = {"invoices:read"})
    void endpoint_invalidEnd_returns400() {
        String amUuid = seedUser("Tommy", "Sørensen");
        given()
                .header("X-Requested-By", "controller")
                .contentType(MediaType.APPLICATION_JSON)
                .body("{\"accountManagerUuid\":\"" + amUuid + "\",\"end\":\"2026-01\"}")
                .when()
                .post(AM_BRIEF_PATH)
                .then()
                .statusCode(400);
    }

    @Test
    @TestTransaction
    @TestSecurity(user = "controller", roles = {"invoices:read"})
    void endpoint_invalidFraming_returns400() {
        String amUuid = seedUser("Tommy", "Sørensen");
        given()
                .header("X-Requested-By", "controller")
                .contentType(MediaType.APPLICATION_JSON)
                .body("{\"accountManagerUuid\":\"" + amUuid + "\",\"framing\":\"FOO\"}")
                .when()
                .post(AM_BRIEF_PATH)
                .then()
                .statusCode(400);
    }

    private String seedUser(String firstname, String lastname) {
        String uuid = UUID.randomUUID().toString();
        em.createNativeQuery("""
                INSERT INTO user (uuid, active, firstname, lastname, email, username, password, type,
                                  created, cpr, birthday)
                VALUES (:uuid, 1, :first, :last, :email, :username, 'x', 'CONSULTANT',
                        NOW(), '0000000000', '2000-01-01')
                """)
                .setParameter("uuid", uuid)
                .setParameter("first", firstname)
                .setParameter("last", lastname)
                .setParameter("email", uuid + "@example.com")
                .setParameter("username", uuid)
                .executeUpdate();
        em.flush();
        return uuid;
    }
}
