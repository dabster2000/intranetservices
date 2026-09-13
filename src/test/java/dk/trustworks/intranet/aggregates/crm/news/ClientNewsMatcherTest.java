package dk.trustworks.intranet.aggregates.crm.news;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.Test;
import java.time.*;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

class ClientNewsMatcherTest {
    final ClientNewsMatcher matcher = new ClientNewsMatcher();
    final ObjectMapper json = new ObjectMapper();
    final Instant now = Instant.parse("2026-09-13T06:00:00Z");
    final ClientNewsRepository.Identity novo = company("Novo Nordisk A/S", "24256790");

    @Test void matchesLegalNameAliasButExcludesFoundationAndHoldings() {
        assertTrue(matcher.select(novo, article("Novo Nordisk appoints a new CEO"), now).isPresent());
        assertTrue(matcher.select(novo, article("Novo Nordisk Foundation invests in a new centre"), now).isEmpty());
        assertTrue(matcher.select(novo, article("Novo Nordisk Fonden investerer i digital forskning"), now).isEmpty());
        assertTrue(matcher.select(novo, article("Novo Holdings appoints a new CEO"), now).isEmpty());
        var bodyOnly = article("Foundation invests in a new research centre");
        bodyOnly.put("body", "The Novo Nordisk Foundation has announced an investment.");
        assertTrue(matcher.select(novo, bodyOnly, now).isEmpty(), "foundation body mention is not the pharmaceutical company");
    }

    @Test void matchesDanishAgencyInBodyForAltIdWithoutAcceptingGreenlandAgency() {
        var digst = company("Digitaliseringsstyrelsen", "");
        var story = article("Børn under 15 år skal holdes ude af sociale medier");
        story.put("body", "Den nye løsning AltID leveres af Digitaliseringsstyrelsen. Den bruges til alderskontrol.");
        var selected = matcher.select(digst, story, now).orElseThrow();
        assertEquals(ClientNewsDTO.Category.DIGITAL_TRANSFORMATION, selected.item().category());
        story.put("body", "Digitaliseringsstyrelsen i Grønland lancerer nyt MitID.");
        assertTrue(matcher.select(digst, story, now).isEmpty());
        story.put("body", "Digitaliseringsstyrelsens nye løsning");
        assertTrue(matcher.select(digst, story, now).isEmpty(), "avoid accidental substring entity matches");
    }

    @Test void matchesInternalLeadershipAndLabelsVattenfallGroupHonestly() {
        assertEquals(ClientNewsDTO.Category.LEADERSHIP, matcher.select(company("Banedanmark", "18632276"),
                article("Banedanmark får ny direktør for Vedligehold"), now).orElseThrow().item().category());
        var vattenfall = company("Vattenfall A/S", "21311332");
        assertEquals(ClientNewsDTO.Scope.GROUP, matcher.select(vattenfall,
                article("Vattenfall invests in new wind projects"), now).orElseThrow().item().scope());
        assertEquals(ClientNewsDTO.Scope.COMPANY, matcher.select(vattenfall,
                article("Vattenfall A/S appoints new director"), now).orElseThrow().item().scope());
    }

    @Test void recognisesVerifiedSecurityInfrastructureAndDataCentreTopics() {
        assertEquals(ClientNewsDTO.Category.DIGITAL_TRANSFORMATION, matcher.select(novo,
                article("Novo Nordisk Data Breach Tied to Stolen GitHub Access Tokens"), now).orElseThrow().item().category());
        assertEquals(ClientNewsDTO.Category.DIGITAL_TRANSFORMATION, matcher.select(company("Vattenfall A/S", "21311332"),
                article("Why Vattenfall joined the great offshore data centre dash"), now).orElseThrow().item().category());
        var rail = article("Nye tal afslører tusindvis af signalfejl"); rail.put("body", "Banedanmark fremlægger tallene.");
        assertEquals(ClientNewsDTO.Category.DIGITAL_TRANSFORMATION, matcher.select(company("Banedanmark", "18632276"),
                rail, now).orElseThrow().item().category());
    }

    @Test void doesNotApplyPharmaceuticalExclusionsToAnActualFoundationAccount() {
        assertTrue(matcher.select(company("Novo Nordisk Fonden", "10582989"),
                article("Novo Nordisk Fonden investerer i forskning"), now).isPresent());
        assertFalse(ClientNewsMatcher.isDigst(company("Digitaliseringsstyrelsen Grønland", "12345678")));
    }

    @Test void rejectsPriceNoiseUnsafeLinksMissingDatesAndFuturePublication() {
        assertTrue(matcher.select(novo, article("Novo Nordisk stock price forecast after earnings"), now).isEmpty());
        var story = article("Novo Nordisk appoints new chief");
        for (String url : List.of("javascript:alert(1)", "https://user:pass@example.com/news", "http://127.0.0.1/a",
                "http://localhost/a", "file:///etc/passwd", "https://host.local/a")) {
            story.put("url", url); assertTrue(matcher.select(novo, story, now).isEmpty(), url);
        }
        story.put("url", "https://example.com/news"); story.remove("dateTimePub");
        assertTrue(matcher.select(novo, story, now).isEmpty());
        story.put("dateTimePub", "2026-09-14T00:00:00Z");
        assertTrue(matcher.select(novo, story, now).isEmpty());
    }

    @Test void removesTrackingAndDeduplicatesArticlesAndStoriesBeforeTakingSixFreshest() {
        List<ClientNewsDTO.StoredItem> stories = new ArrayList<>();
        for (int i = 0; i < 9; i++) {
            var row = article("Novo Nordisk appoints director " + i);
            row.put("uri", "article-" + i); row.put("eventUri", "event-" + (i / 2));
            row.put("url", "https://news.example.com/" + i + "?utm_source=test#tracking");
            row.put("dateTimePub", "2026-09-" + String.format("%02d", i + 1) + "T12:00:00Z");
            stories.add(matcher.select(novo, row, now).orElseThrow());
        }
        var kept = matcher.latest(stories, stories);
        assertEquals(5, kept.size());
        assertEquals("https://news.example.com/8", kept.getFirst().item().url());
        assertEquals(Instant.parse("2026-09-09T12:00:00Z"), kept.getFirst().item().publishedAt());
        assertTrue(kept.getFirst().item().summary().split("\\s+").length <= 20);
    }

    @Test void articlePublicationTakesPrecedenceOverIndexTimestampAndTextCleanupIsBounded() {
        var row = article("Novo Nordisk launches new programme");
        row.put("dateTime", "2026-09-13T04:00:00Z");
        assertEquals(Instant.parse("2026-09-10T10:00:00Z"), ClientNewsMatcher.publication(row));
        assertTimeout(Duration.ofSeconds(1), () -> assertEquals("", ClientNewsMatcher.plain("<".repeat(1_000_000), 120_000)));
        assertEquals("Hello world", ClientNewsMatcher.plain("<b>Hello</b>\nworld", 100));
    }

    ObjectNode article(String title) {
        ObjectNode row = json.createObjectNode();
        row.put("title", title); row.put("uri", UUID.randomUUID().toString());
        row.put("url", "https://news.example.com/article"); row.put("dateTimePub", "2026-09-10T10:00:00Z");
        row.put("body", "one two three four five six seven eight nine ten eleven twelve thirteen fourteen fifteen sixteen seventeen eighteen nineteen twenty twenty-one");
        row.putObject("source").put("title", "Example publication");
        return row;
    }
    static ClientNewsRepository.Identity company(String name, String cvr) {
        return new ClientNewsRepository.Identity("11111111-1111-1111-1111-111111111111", name, cvr);
    }
}
