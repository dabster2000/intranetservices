package dk.trustworks.intranet.aggregates.crm.enrichment.ai;

import com.fasterxml.jackson.databind.ObjectMapper;
import dk.trustworks.intranet.aggregates.crm.enrichment.ClientEnrichmentConfig;
import dk.trustworks.intranet.aggregates.crm.enrichment.ClientEnrichmentTestConfig;
import dk.trustworks.intranet.dao.crm.model.enums.ClientSegment;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Everything the backend does to a model answer before it is allowed to matter — plain
 * JUnit, no Quarkus, no network. The model proposes; these are the rules that dispose.
 */
class EnrichmentParsersTest {

    private CvrCandidateFinder cvr;
    private LogoFinder logo;
    private SectorClassifier sector;

    @BeforeEach
    void setUp() {
        ClientEnrichmentConfig config = ClientEnrichmentTestConfig.defaults();
        ObjectMapper mapper = new ObjectMapper();
        cvr = new CvrCandidateFinder();
        cvr.config = config;
        cvr.objectMapper = mapper;
        logo = new LogoFinder();
        logo.config = config;
        logo.objectMapper = mapper;
        sector = new SectorClassifier();
        sector.config = config;
        sector.objectMapper = mapper;
    }

    // ---- CVR ------------------------------------------------------------------

    @Test
    void cvrCandidateIsReadWithSpacesAndDashesRemoved() {
        Optional<CvrCandidateFinder.Candidate> c = cvr.parse(
                "{\"cvr\":\"26 57 35-72\",\"registryName\":\"Fiskars Denmark (Vita) A/S\",\"confidence\":0.93,\"sourceUrl\":\"https://datacvr.virk.dk/x\"}");
        assertTrue(c.isPresent());
        assertEquals("26573572", c.get().cvr());
        assertEquals("Fiskars Denmark (Vita) A/S", c.get().registryName());
        assertEquals(0.93, c.get().confidence(), 0.0001);
        assertEquals("https://datacvr.virk.dk/x", c.get().sourceUrl());
    }

    @Test
    void cvrCandidateThatIsNotEightDigitsIsDropped() {
        assertTrue(cvr.parse("{\"cvr\":\"2657357\",\"registryName\":\"X\",\"confidence\":0.9,\"sourceUrl\":null}").isEmpty());
        assertTrue(cvr.parse("{\"cvr\":\"DK26573572\",\"registryName\":\"X\",\"confidence\":0.9,\"sourceUrl\":null}").isEmpty());
        assertTrue(cvr.parse("{\"cvr\":null,\"registryName\":null,\"confidence\":0,\"sourceUrl\":null}").isEmpty());
        assertTrue(cvr.parse("{}").isEmpty());
        assertTrue(cvr.parse(null).isEmpty());
        assertTrue(cvr.parse("not json").isEmpty());
    }

    @Test
    void cvrConfidenceIsClampedAndNullStringsAreNull() {
        Optional<CvrCandidateFinder.Candidate> c = cvr.parse(
                "{\"cvr\":\"26573572\",\"registryName\":\"null\",\"confidence\":7,\"sourceUrl\":\"\"}");
        assertTrue(c.isPresent());
        assertEquals(1.0, c.get().confidence(), 0.0001);
        assertEquals(null, c.get().registryName());
        assertEquals(null, c.get().sourceUrl());
    }

    // ---- Logo -----------------------------------------------------------------

    @Test
    void logoLocationRequiresHttpsAndARasterFile() {
        assertTrue(logo.parse("{\"found\":true,\"imageUrl\":\"https://fiskars.dk/logo.png\",\"pageUrl\":\"https://fiskars.dk\",\"confidence\":0.8}").isPresent());
        assertTrue(logo.parse("{\"found\":true,\"imageUrl\":\"http://fiskars.dk/logo.png\",\"pageUrl\":null,\"confidence\":0.8}").isEmpty(), "http");
        assertTrue(logo.parse("{\"found\":true,\"imageUrl\":\"https://fiskars.dk/logo.svg\",\"pageUrl\":null,\"confidence\":0.8}").isEmpty(), "svg");
        assertTrue(logo.parse("{\"found\":true,\"imageUrl\":\"https://fiskars.dk/logo.SVG?v=2\",\"pageUrl\":null,\"confidence\":0.8}").isEmpty(), "svg with query");
        assertTrue(logo.parse("{\"found\":false,\"imageUrl\":\"https://fiskars.dk/logo.png\",\"pageUrl\":null,\"confidence\":0.8}").isEmpty(), "found=false wins");
        assertTrue(logo.parse("{\"found\":true,\"imageUrl\":null,\"pageUrl\":null,\"confidence\":0.8}").isEmpty(), "no url");
        assertTrue(logo.parse("{}").isEmpty());
    }

    // ---- Sector ---------------------------------------------------------------

    @Test
    void sectorVerdictMapsToTheEnumAndKeepsTheReason() {
        Optional<SectorClassifier.Verdict> v = sector.parse(
                "{\"segment\":\"financial\",\"confidence\":0.88,\"reason\":\"A mortgage bank.\"}");
        assertTrue(v.isPresent());
        assertEquals(ClientSegment.FINANCIAL, v.get().segment());
        assertEquals(0.88, v.get().confidence(), 0.0001);
        assertEquals("A mortgage bank.", v.get().reason());
    }

    @Test
    void unknownSectorOrNoAnswerIsEmpty() {
        assertTrue(sector.parse("{\"segment\":\"RETAIL\",\"confidence\":0.9,\"reason\":\"x\"}").isEmpty());
        assertTrue(sector.parse("{\"segment\":\"\",\"confidence\":0.9,\"reason\":\"x\"}").isEmpty());
        assertTrue(sector.parse("{}").isEmpty());
        assertTrue(sector.parse("garbage").isEmpty());
    }

    @Test
    void sectorSchemaListsExactlyTheSixSegments() {
        var schema = SectorClassifier.schema(new ObjectMapper());
        var values = schema.path("properties").path("segment").path("enum");
        assertEquals(ClientSegment.values().length, values.size());
        assertFalse(schema.path("additionalProperties").asBoolean(true));
    }

    @Test
    void promptsCarryTheFactsTheyAreGiven() {
        String p = SectorClassifier.userPrompt("Nykredit", "12719280", 641900, "Anden pengeinstitutvirksomhed", "København");
        assertTrue(p.contains("Nykredit") && p.contains("12719280") && p.contains("641900") && p.contains("København"));
        String q = CvrCandidateFinder.userPrompt("Nykredit", null, "1780");
        assertTrue(q.contains("Nykredit") && q.contains("1780") && !q.contains("City"));
        String l = LogoGenerator.prompt("Nykredit", null, ClientSegment.FINANCIAL);
        assertTrue(l.contains("Nykredit") && l.contains("financial services"));
    }
}
