package dk.trustworks.intranet.aggregates.crm.slack.services;

import com.fasterxml.jackson.databind.ObjectMapper;
import dk.trustworks.intranet.aggregates.crm.slack.dto.SlackDigestContent;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The validation between the model and the database. Everything the model returns
 * passes through {@link AccountSlackDigestService#parse}; what is locked here is that
 * the caps hold, that what the prompt forbids is stripped even when the model ignores
 * the prompt, and that a model's self-contradiction resolves in the reader's favour.
 *
 * <p>Fast tier — no Quarkus boot, no OpenAI, no database.
 */
class AccountSlackDigestServiceTest {

    private AccountSlackDigestService service;

    @BeforeEach
    void setUp() {
        service = new AccountSlackDigestService();
        service.objectMapper = new ObjectMapper();
        service.digestModel = "test-model";
    }

    // ------------------------------------------------------------------------
    // Degradation: OpenAIService never throws, it answers "{}"
    // ------------------------------------------------------------------------

    @Test
    void noOutputIsAnEmptyReadingNotAnException() {
        assertTrue(service.parse(null).isEmpty());
        assertTrue(service.parse("").isEmpty());
        assertTrue(service.parse("{}").isEmpty());
        assertTrue(service.parse("not json").isEmpty());
        assertTrue(service.parse("[1,2]").isEmpty());
    }

    // ------------------------------------------------------------------------
    // A good day
    // ------------------------------------------------------------------------

    @Test
    void aFullReadingComesThroughIntact() {
        Optional<SlackDigestContent> read = service.parse("""
                {"headline":"Reelle ejere skubbes til efter kick-off; arkitekturtegning klar torsdag",
                 "relevance":"HIGH",
                 "decisions":[{"text":"Systemet bygges uden reelle ejere; de tilføjes senere","who":"Lars","when":null}],
                 "nextSteps":[{"text":"Labels og rollemapping på arkitekturtegningen","who":"Jesper","when":"2026-09-10"}],
                 "risks":[{"text":"E-nettet når måske ikke at vurdere reelle ejere inden kick-off"}],
                 "clientAsks":[{"text":"Afsnit 5 om datakilder afventer E-nettet"}],
                 "clientPeople":[{"name":"Lars","role":null},{"name":"Laura","role":"projektleder"}],
                 "topics":["reelle ejere","arkitektur","GRC"],
                 "confidence":0.8}
                """);
        assertTrue(read.isPresent());
        SlackDigestContent content = read.get();
        assertEquals("Reelle ejere skubbes til efter kick-off; arkitekturtegning klar torsdag", content.headline());
        assertEquals("HIGH", content.relevance());
        assertEquals(1, content.decisions().size());
        assertEquals("Lars", content.decisions().get(0).who());
        assertNull(content.decisions().get(0).when());
        assertEquals("2026-09-10", content.nextSteps().get(0).when());
        assertEquals("Laura", content.clientPeople().get(1).name());
        assertEquals("projektleder", content.clientPeople().get(1).role());
        assertEquals(3, content.topics().size());
        assertEquals(0.8d, content.confidence(), 0.0001);
        assertTrue(content.hasDetails());
    }

    @Test
    void theStoredJsonRoundTrips() {
        SlackDigestContent content = service.parse("""
                {"headline":"h","relevance":"LOW","decisions":[],"nextSteps":[{"text":"t","who":null,"when":"2026-09-10"}],
                 "risks":[],"clientAsks":[],"clientPeople":[],"topics":["x"],"confidence":0.5}
                """).orElseThrow();
        String json = service.toJson(content);
        assertNotNull(json);
        SlackDigestContent back = service.fromJson(json);
        assertEquals(content, back);
        assertNull(service.fromJson(null));
        assertNull(service.fromJson("broken"));
    }

    // ------------------------------------------------------------------------
    // What the prompt forbids is stripped regardless
    // ------------------------------------------------------------------------

    @Test
    void addressesLinksAndNumbersAreStrippedFromEveryString() {
        SlackDigestContent content = service.parse("""
                {"headline":"Skriv til lars@e-nettet.dk om https://example.com/x",
                 "relevance":"HIGH",
                 "decisions":[{"text":"Ring +45 12 34 56 78 til Lars","who":"Lars <lars@e-nettet.dk>","when":null}],
                 "nextSteps":[],"risks":[],"clientAsks":[],
                 "clientPeople":[{"name":"Lars (lars@e-nettet.dk)","role":null}],
                 "topics":["https://x.dk"],"confidence":1}
                """).orElseThrow();
        assertEquals("Skriv til [e-mail] om [link]", content.headline());
        assertEquals("Ring [phone] til Lars", content.decisions().get(0).text());
        assertEquals("Lars", content.decisions().get(0).who(), "the tag is stripped by the sanitizer");
        assertEquals("Lars ([e-mail])", content.clientPeople().get(0).name());
        assertEquals("[link]", content.topics().get(0));
    }

    @Test
    void aDateThatIsNotIsoIsDroppedNotGuessed() {
        assertNull(AccountSlackDigestService.isoDateOrNull("torsdag"));
        assertNull(AccountSlackDigestService.isoDateOrNull("10/09/2026"));
        assertNull(AccountSlackDigestService.isoDateOrNull("2026-13-40"));
        assertNull(AccountSlackDigestService.isoDateOrNull(null));
        assertEquals("2026-09-10", AccountSlackDigestService.isoDateOrNull(" 2026-09-10 "));
    }

    // ------------------------------------------------------------------------
    // Caps
    // ------------------------------------------------------------------------

    @Test
    void listsAreCappedAndPeopleAndTopicsDeduplicated() {
        StringBuilder decisions = new StringBuilder();
        for (int i = 0; i < 20; i++) {
            if (i > 0) decisions.append(',');
            decisions.append("{\"text\":\"d").append(i).append("\",\"who\":null,\"when\":null}");
        }
        SlackDigestContent content = service.parse("{"
                + "\"headline\":null,\"relevance\":\"HIGH\","
                + "\"decisions\":[" + decisions + "],"
                + "\"nextSteps\":[],\"risks\":[],\"clientAsks\":[],"
                + "\"clientPeople\":[{\"name\":\"Lars\",\"role\":null},{\"name\":\"lars\",\"role\":\"CIO\"},{\"name\":\"\",\"role\":null}],"
                + "\"topics\":[\"GRC\",\"grc\",\"\",\"arkitektur\"],"
                + "\"confidence\":7}").orElseThrow();
        assertEquals(AccountSlackDigestService.MAX_ITEMS_PER_LIST, content.decisions().size());
        assertEquals(1, content.clientPeople().size(), "the same person twice is one person");
        assertEquals(2, content.topics().size());
        assertEquals(1.0d, content.confidence(), 0.0001, "clamped");
    }

    @Test
    void anOverlongHeadlineIsCutToTheColumn() {
        String longLine = "x".repeat(400);
        SlackDigestContent content = service.parse("{\"headline\":\"" + longLine + "\",\"relevance\":\"LOW\","
                + "\"decisions\":[],\"nextSteps\":[],\"risks\":[],\"clientAsks\":[],\"clientPeople\":[],\"topics\":[],"
                + "\"confidence\":0.5}").orElseThrow();
        assertEquals(AccountSlackDigestService.MAX_HEADLINE_CHARS, content.headline().length());
    }

    @Test
    void anItemWithoutTextIsNotAnItem() {
        SlackDigestContent content = service.parse("""
                {"headline":null,"relevance":"NONE","decisions":[{"text":"","who":"Lars","when":null},{"text":null,"who":null,"when":null}],
                 "nextSteps":[],"risks":[{"text":"   "}],"clientAsks":[],"clientPeople":[],"topics":[],"confidence":0}
                """).orElseThrow();
        assertTrue(content.decisions().isEmpty());
        assertTrue(content.risks().isEmpty());
        assertFalse(content.hasDetails());
        assertEquals("NONE", content.relevance());
    }

    // ------------------------------------------------------------------------
    // Relevance is reconciled with the content
    // ------------------------------------------------------------------------

    @Test
    void relevanceFollowsTheContentWhenTheModelContradictsItself() {
        assertEquals("NONE", AccountSlackDigestService.relevance("NONE", null, false));
        assertEquals("LOW", AccountSlackDigestService.relevance("NONE", "a headline", false), "a headline is content");
        assertEquals("HIGH", AccountSlackDigestService.relevance("NONE", null, true), "a decision is HIGH whatever the label");
        assertEquals("HIGH", AccountSlackDigestService.relevance("LOW", "h", true));
        assertEquals("LOW", AccountSlackDigestService.relevance("LOW", "h", false));
        assertEquals("HIGH", AccountSlackDigestService.relevance("high", null, false), "case-insensitive");
        assertEquals("NONE", AccountSlackDigestService.relevance("banana", null, false));
        assertEquals("LOW", AccountSlackDigestService.relevance(null, "h", false));
    }
}
