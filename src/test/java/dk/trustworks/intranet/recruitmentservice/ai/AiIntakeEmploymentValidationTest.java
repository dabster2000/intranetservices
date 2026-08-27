package dk.trustworks.intranet.recruitmentservice.ai;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Unit contract for the brief-v2 {@code employment} section — the workplaces
 * the model reads out of a CV.
 * <p>
 * The governing rule, and the reason this test exists separately from
 * {@link AiIntakeBriefValidationTest}: employment NEVER fails a generation.
 * The bullets are what the interviewer asked for; the history rides along
 * with them, so a model that fumbles a date must cost the reader that date
 * and nothing else. Every case below is therefore a drop, not a throw — with
 * one exception, a scratchpad tell, which drops the whole section on the same
 * reasoning the bullets use: a model that broke channel discipline once
 * cannot be trusted for the entries around it.
 * <p>
 * Deliberately a plain JUnit test, not a {@code @QuarkusTest}: only this
 * DB-free tier runs in the CI deploy gate.
 */
class AiIntakeEmploymentValidationTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private final AiIntakeGenerationService service = new AiIntakeGenerationService();

    private static JsonNode node(String json) {
        try {
            return MAPPER.readTree(json);
        } catch (Exception e) {
            throw new AssertionError("test fixture is not valid JSON", e);
        }
    }

    // ---- The happy path --------------------------------------------------------

    @Test
    void wellFormedHistory_survivesInModelOrder() {
        List<AiIntakeGenerationService.Employment> history = service.validateEmployment(node("""
                [{"employer":"Netcompany","title":"Senior konsulent",
                  "startDate":"2021-03","endDate":null,"current":true},
                 {"employer":"KMD","title":"Udvikler",
                  "startDate":"2017","endDate":"2021-02","current":false}]"""));

        assertEquals(2, history.size());
        assertEquals("Netcompany", history.get(0).employer());
        assertEquals("Senior konsulent", history.get(0).title());
        assertEquals("2021-03", history.get(0).startDate());
        assertNull(history.get(0).endDate());
        assertTrue(history.get(0).current(), "the prompt asks for newest first — order is preserved");
        assertEquals("2021-02", history.get(1).endDate());
        assertFalse(history.get(1).current());
    }

    @Test
    void aWorkplaceWithNeitherTitleNorDates_stillNamesAnEmployer() {
        List<AiIntakeGenerationService.Employment> history = service.validateEmployment(node("""
                [{"employer":"Trustworks","title":null,"startDate":null,
                  "endDate":null,"current":false}]"""));

        assertEquals(1, history.size(), "a company with no readable period is still a real job");
        assertEquals("Trustworks", history.get(0).employer());
        assertNull(history.get(0).title());
    }

    // ---- Unreadable dates are dropped, the workplace is not --------------------

    @Test
    void periodsACvActuallyWrites_thatWeCannotParse_becomeNull() {
        List<AiIntakeGenerationService.Employment> history = service.validateEmployment(node("""
                [{"employer":"Netcompany","title":"Konsulent","startDate":"efter\\u00e5r 2019",
                  "endDate":"present","current":true},
                 {"employer":"KMD","title":null,"startDate":"2017-13","endDate":"17-2020",
                  "current":false}]"""));

        assertEquals(2, history.size(), "an unreadable period never costs the employer");
        assertNull(history.get(0).startDate(), "\"efterår 2019\" is not a date we will persist");
        assertNull(history.get(0).endDate());
        assertNull(history.get(1).startDate(), "month 13 does not exist");
        assertNull(history.get(1).endDate(), "\"17-2020\" is not YYYY[-MM]");
    }

    @Test
    void aBareYearAndAYearMonth_areBothAccepted() {
        List<AiIntakeGenerationService.Employment> history = service.validateEmployment(node("""
                [{"employer":"A","title":null,"startDate":"2015","endDate":"2019-12","current":false}]"""));

        assertEquals("2015", history.get(0).startDate());
        assertEquals("2019-12", history.get(0).endDate(), "December is the boundary month");
    }

    // ---- Entry-level drops ----------------------------------------------------

    @Test
    void anEntryWithoutAnEmployer_isDropped_theRestSurvives() {
        List<AiIntakeGenerationService.Employment> history = service.validateEmployment(node("""
                [{"employer":"   ","title":"Konsulent","startDate":"2021","endDate":null,"current":true},
                 {"employer":"KMD","title":null,"startDate":"2017","endDate":"2021","current":false}]"""));

        assertEquals(1, history.size(), "an entry naming no workplace names nothing");
        assertEquals("KMD", history.get(0).employer());
    }

    @Test
    void anOverLongEmployer_isDropped_notTruncated() {
        String tooLong = "N".repeat(AiIntakeGenerationService.MAX_EMPLOYMENT_TEXT_CHARS + 1);
        List<AiIntakeGenerationService.Employment> history = service.validateEmployment(node("""
                [{"employer":"%s","title":null,"startDate":null,"endDate":null,"current":false},
                 {"employer":"KMD","title":null,"startDate":null,"endDate":null,"current":false}]"""
                .formatted(tooLong)));

        assertEquals(1, history.size(), "half a company name is a fabrication, not a workplace");
        assertEquals("KMD", history.get(0).employer());
    }

    @Test
    void moreEntriesThanTheCap_areTruncatedToTheCap() {
        StringBuilder json = new StringBuilder("[");
        for (int i = 0; i < AiIntakeGenerationService.MAX_EMPLOYMENT_ENTRIES + 5; i++) {
            json.append(i == 0 ? "" : ",")
                .append("{\"employer\":\"Firma ").append(i)
                .append("\",\"title\":null,\"startDate\":null,\"endDate\":null,\"current\":false}");
        }
        json.append(']');

        assertEquals(AiIntakeGenerationService.MAX_EMPLOYMENT_ENTRIES,
                service.validateEmployment(node(json.toString())).size());
    }

    // ---- Section-level drops ---------------------------------------------------

    @Test
    void aScratchpadTellAnywhere_dropsTheWholeSection() {
        List<AiIntakeGenerationService.Employment> history = service.validateEmployment(node("""
                [{"employer":"Netcompany","title":"Konsulent","startDate":"2021",
                  "endDate":null,"current":true},
                 {"employer":"assistant to=final {\\"employment\\": [",
                  "title":null,"startDate":null,"endDate":null,"current":false}]"""));

        assertTrue(history.isEmpty(),
                "one broken-channel entry discredits the entries around it");
    }

    @Test
    void aMissingOrNullOrNonArraySection_isEmpty_neverAThrow() {
        assertTrue(service.validateEmployment(node("null")).isEmpty());
        assertTrue(service.validateEmployment(node("{}")).isEmpty(), "an object is not a history");
        assertTrue(service.validateEmployment(node("[]")).isEmpty());
        assertTrue(service.validateEmployment(MAPPER.createObjectNode().path("employment")).isEmpty(),
                "a missing section must not cost the reader their bullets");
    }

    @Test
    void nonObjectItems_areSkipped() {
        List<AiIntakeGenerationService.Employment> history = service.validateEmployment(node("""
                ["Netcompany 2021-",{"employer":"KMD","title":null,"startDate":null,
                  "endDate":null,"current":false}]"""));

        assertEquals(1, history.size());
        assertEquals("KMD", history.get(0).employer());
    }

    // ---- The schema the model is held to ---------------------------------------

    @Test
    void briefSchema_declaresEmployment_andIntakeOnlyDoesNot() {
        ObjectNode withBrief = AiIntakePrompts.schema(false, true);
        JsonNode employment = withBrief.path("properties").path("employment");

        assertFalse(employment.isMissingNode(), "the brief section carries the history");
        assertTrue(withBrief.path("required").toString().contains("employment"),
                "strict structured outputs require every declared property");
        assertEquals(AiIntakeGenerationService.MAX_EMPLOYMENT_ENTRIES,
                employment.path("maxItems").asInt());
        assertEquals("[\"array\",\"null\"]", employment.path("type").toString(),
                "nullable — a CV with no history is not an error");

        JsonNode item = employment.path("items");
        assertFalse(item.path("additionalProperties").asBoolean(true));
        assertEquals("[\"employer\",\"title\",\"startDate\",\"endDate\",\"current\"]",
                item.path("required").toString());
        assertEquals("string", item.path("properties").path("employer").path("type").asText(),
                "the one field an entry cannot do without");

        assertTrue(AiIntakePrompts.schema(true, false).path("properties").path("employment")
                        .isMissingNode(),
                "an intake-only call must not ask for a history it will not append");
    }

    @Test
    void briefPromptVersion_isStampedV2() {
        assertEquals("brief-v2", AiIntakePrompts.PROMPT_VERSION_BRIEF,
                "the read side compares against this literal to offer a regeneration");
    }
}
