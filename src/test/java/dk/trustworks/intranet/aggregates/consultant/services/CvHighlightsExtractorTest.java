package dk.trustworks.intranet.aggregates.consultant.services;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.MissingNode;
import dk.trustworks.intranet.aggregates.consultant.services.CvHighlightsExtractor.CvHighlights;
import dk.trustworks.intranet.dao.crm.model.enums.ClientSegment;
import org.junit.jupiter.api.Test;

import java.time.Year;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Plain JUnit — deliberately NOT {@code @QuarkusTest}. A {@code @QuarkusTest} that aborts at boot
 * is reported as a green SKIP in this repo, which would make this whole file vacuous.
 *
 * <p>{@link CvHighlightsExtractor} is the deterministic half of the dashboard "Available Now" card:
 * the facts that are rendered even when the AI half is PENDING, UNAVAILABLE or switched off. It is
 * also the control that keeps former-employer client names ({@code client_is_trustworks == false})
 * off a Trustworks sales card.
 */
class CvHighlightsExtractorTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private final CvHighlightsExtractor extractor = new CvHighlightsExtractor();

    private static JsonNode cv(String json) {
        try {
            return MAPPER.readTree(json);
        } catch (Exception e) {
            throw new AssertionError("test fixture is not valid JSON", e);
        }
    }

    // ---- 1. roleTitle ----------------------------------------------------------

    @Test
    void roleTitle_isTrimmedAndWhitespaceCollapsed() {
        CvHighlights highlights = extractor.extract("  Senior   Project    Manager  ",
                cv("{\"projects\":[]}"), Map.of());

        assertEquals("Senior Project Manager", highlights.roleTitle());
    }

    @Test
    void roleTitle_blankOrNull_becomesNull() {
        assertNull(extractor.extract("   ", cv("{}"), Map.of()).roleTitle());
        assertNull(extractor.extract(null, cv("{}"), Map.of()).roleTitle());
    }

    @Test
    void roleTitle_longerThan120Chars_isTruncatedTo120WithoutEllipsis() {
        String title = "A".repeat(200);

        String actual = extractor.extract(title, cv("{}"), Map.of()).roleTitle();

        assertNotNull(actual);
        assertEquals(120, actual.length());
        assertFalse(actual.endsWith("…"), "the frontend truncates visually; the payload must not carry an ellipsis");
    }

    // ---- 2/3. industries -------------------------------------------------------

    @Test
    void industries_areDerivedFromSegments_rankedByCount_otherDropped_cappedAtTwo() {
        JsonNode root = cv("""
                {"projects":[
                  {"client_uuid":"c-public","client_name":"Kommune","client_is_trustworks":true},
                  {"client_uuid":"c-public","client_name":"Kommune","client_is_trustworks":true},
                  {"client_uuid":"c-public","client_name":"Kommune","client_is_trustworks":true},
                  {"client_uuid":"c-fin","client_name":"Bank","client_is_trustworks":true},
                  {"client_uuid":"c-fin","client_name":"Bank","client_is_trustworks":true},
                  {"client_uuid":"c-energy","client_name":"Utility","client_is_trustworks":true},
                  {"client_uuid":"c-other","client_name":"Misc","client_is_trustworks":true},
                  {"client_uuid":"c-other","client_name":"Misc","client_is_trustworks":true},
                  {"client_uuid":"c-other","client_name":"Misc","client_is_trustworks":true},
                  {"client_uuid":"c-other","client_name":"Misc","client_is_trustworks":true},
                  {"client_uuid":"c-other","client_name":"Misc","client_is_trustworks":true}
                ]}""");
        Map<String, ClientSegment> segments = Map.of(
                "c-public", ClientSegment.PUBLIC,
                "c-fin", ClientSegment.FINANCIAL,
                "c-energy", ClientSegment.ENERGY,
                // OTHER carries no sales signal even though it is the most frequent segment here.
                "c-other", ClientSegment.OTHER);

        CvHighlights highlights = extractor.extract("Consultant", root, segments);

        assertEquals(java.util.List.of("Public Sector", "Financial Services"), highlights.industries());
    }

    @Test
    void industries_areEmptyWhenNoClientUuidResolves_soTheAiFallbackTakesOver() {
        JsonNode root = cv("""
                {"projects":[
                  {"client_uuid":"unknown-1","client_name":"X","client_is_trustworks":true},
                  {"client_name":"Y","client_is_trustworks":true}
                ]}""");

        CvHighlights highlights = extractor.extract("Consultant", root,
                Map.of("some-other-client", ClientSegment.PUBLIC));

        assertTrue(highlights.industries().isEmpty());
    }

    // ---- 4/5/6. clients --------------------------------------------------------

    @Test
    void topClients_excludeNonTrustworksProjectsAndTrustworksItself() {
        JsonNode root = cv("""
                {"projects":[
                  {"client_name":"Microsoft","client_is_trustworks":false},
                  {"client_name":"Lunar Bank","client_is_trustworks":false},
                  {"client_name":"Trustworks","client_is_trustworks":true},
                  {"client_name":"trustworks","client_is_trustworks":true},
                  {"client_name":"Nordea","client_is_trustworks":true}
                ]}""");

        CvHighlights highlights = extractor.extract("Consultant", root, Map.of());

        assertEquals(java.util.List.of("Nordea"), highlights.topClients());
        assertEquals(1, highlights.clientCount());
        // projectCount counts every project, matched or not.
        assertEquals(5, highlights.projectCount());
    }

    @Test
    void topClients_collapseCaseVariants_andPreferTheMixedCaseSpelling() {
        JsonNode root = cv("""
                {"projects":[
                  {"client_name":"PFA PENSION ","client_is_trustworks":true},
                  {"client_name":"PFA Pension","client_is_trustworks":true}
                ]}""");

        CvHighlights highlights = extractor.extract("Consultant", root, Map.of());

        assertEquals(java.util.List.of("PFA Pension"), highlights.topClients());
        assertEquals(1, highlights.clientCount());
    }

    @Test
    void clientCount_countsEveryDistinctClient_notJustTheThreeShown() {
        JsonNode root = cv("""
                {"projects":[
                  {"client_name":"Alpha","client_is_trustworks":true},
                  {"client_name":"Alpha","client_is_trustworks":true},
                  {"client_name":"Alpha","client_is_trustworks":true},
                  {"client_name":"Beta","client_is_trustworks":true},
                  {"client_name":"Beta","client_is_trustworks":true},
                  {"client_name":"Gamma","client_is_trustworks":true},
                  {"client_name":"Delta","client_is_trustworks":true},
                  {"client_name":"Epsilon","client_is_trustworks":true}
                ]}""");

        CvHighlights highlights = extractor.extract("Consultant", root, Map.of());

        assertEquals(3, highlights.topClients().size());
        assertEquals(java.util.List.of("Alpha", "Beta"), highlights.topClients().subList(0, 2));
        assertEquals(5, highlights.clientCount());
    }

    // ---- 7. dates --------------------------------------------------------------

    @Test
    void firstProjectYear_isNullWhenNothingParses_andRejectsOutOfRangeYears() {
        JsonNode unparseable = cv("""
                {"projects":[
                  {"client_name":"A","client_is_trustworks":true,"start_date":""},
                  {"client_name":"B","client_is_trustworks":true,"start_date":"01-02-2020"},
                  {"client_name":"C","client_is_trustworks":true}
                ]}""");
        assertNull(extractor.extract("Consultant", unparseable, Map.of()).firstProjectYear(),
                "Danish DD-MM-YYYY must not be mistaken for a year — inventing a year on a sales card is worse than omitting one");

        JsonNode outOfRange = cv("""
                {"projects":[
                  {"client_name":"A","client_is_trustworks":true,"start_date":"1899-05-01"},
                  {"client_name":"B","client_is_trustworks":true,"start_date":"%d-01-01"},
                  {"client_name":"C","client_is_trustworks":true,"start_date":"2014-03-01"}
                ]}""".formatted(Year.now().getValue() + 5));
        assertEquals(2014, extractor.extract("Consultant", outOfRange, Map.of()).firstProjectYear());
    }

    @Test
    void firstProjectYear_acceptsYearAndYearMonthDegradations() {
        JsonNode root = cv("""
                {"projects":[
                  {"client_name":"A","client_is_trustworks":true,"start_date":"2011"},
                  {"client_name":"B","client_is_trustworks":true,"start_date":"2013-07"}
                ]}""");

        assertEquals(2011, extractor.extract("Consultant", root, Map.of()).firstProjectYear());
    }

    // ---- 8/9. degenerate CVs ---------------------------------------------------

    @Test
    void titleOnlyCv_withNoArrays_yieldsRoleTitleAndNothingElse() {
        // The 202-byte CV shape seen in production (Kasper Kronborg / Lars Albert).
        JsonNode root = cv("{\"ID\":1,\"CV_Title\":\"CV\",\"Employee_Title\":\"Developer\"}");

        CvHighlights highlights = extractor.extract("Developer", root, Map.of());

        assertEquals("Developer", highlights.roleTitle());
        assertTrue(highlights.industries().isEmpty());
        assertTrue(highlights.topClients().isEmpty());
        assertEquals(0, highlights.projectCount());
        assertEquals(0, highlights.clientCount());
        assertNull(highlights.firstProjectYear());
    }

    @Test
    void missingOrNonArrayProjects_degradeToTitleOnly_neverThrow() {
        // The read path hands a MissingNode over when cv_data_json will not parse.
        CvHighlights fromMissing = extractor.extract("Architect", MissingNode.getInstance(), Map.of());
        assertEquals("Architect", fromMissing.roleTitle());
        assertEquals(0, fromMissing.projectCount());

        CvHighlights fromNull = extractor.extract("Architect", null, Map.of());
        assertEquals("Architect", fromNull.roleTitle());

        CvHighlights fromWrongType = extractor.extract("Architect", cv("{\"projects\":\"nope\"}"), Map.of());
        assertEquals("Architect", fromWrongType.roleTitle());
        assertEquals(0, fromWrongType.projectCount());
    }

    @Test
    void projectEntriesThatAreNotObjects_areToleratedNotFatal() {
        JsonNode root = cv("{\"projects\":[\"a string\",42,null,{\"client_name\":\"Nordea\",\"client_is_trustworks\":true}]}");

        CvHighlights highlights = extractor.extract("Consultant", root, Map.of());

        assertEquals(4, highlights.projectCount());
        assertEquals(java.util.List.of("Nordea"), highlights.topClients());
    }
}
