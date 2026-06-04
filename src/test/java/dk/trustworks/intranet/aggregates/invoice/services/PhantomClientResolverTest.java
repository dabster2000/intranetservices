package dk.trustworks.intranet.aggregates.invoice.services;

import dk.trustworks.intranet.aggregates.invoice.model.dto.PhantomClientSuggestion;
import dk.trustworks.intranet.aggregates.invoice.model.enums.SuggestionMethod;
import dk.trustworks.intranet.dao.crm.model.Client;
import dk.trustworks.intranet.dao.crm.services.ClientService;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

/** Pure unit test for the static prefix-strip helper — no CDI, no DB. */
class PhantomClientResolverTest {

    @Test
    void stripsKonsulenthonorarPrefix_caseInsensitive() {
        assertEquals("Vattenfall",
                PhantomClientResolver.stripKnownPrefixes("Konsulenthonorar Vattenfall"));
        assertEquals("Energinet",
                PhantomClientResolver.stripKnownPrefixes("konsulenthonorar Energinet"));
    }

    @Test
    void stripsSalgPrefix() {
        assertEquals("kantineordning",
                PhantomClientResolver.stripKnownPrefixes("Salg kantineordning"));
    }

    @Test
    void leavesUnprefixedLabelUntouched_andTrims() {
        assertEquals("Magnit Global",
                PhantomClientResolver.stripKnownPrefixes("  Magnit Global  "));
    }

    @Test
    void nullAndBlankAreSafe() {
        assertEquals("", PhantomClientResolver.stripKnownPrefixes(null));
        assertEquals("", PhantomClientResolver.stripKnownPrefixes("   "));
    }

    private static Client client(String uuid, String name) {
        Client c = new Client();
        c.setUuid(uuid);
        c.setName(name);
        return c;
    }

    @Test
    void exactMatch_onStrippedLabel_givesConfidenceOne() {
        ClientService cs = mock(ClientService.class);
        when(cs.findByExactNameIgnoreCase("Energinet"))
                .thenReturn(client("16e3ccad-aaaa", "Energinet"));

        PhantomClientResolver resolver = new PhantomClientResolver(cs);
        PhantomClientSuggestion s = resolver.suggest("Konsulenthonorar Energinet");

        assertEquals(SuggestionMethod.EXACT, s.method());
        assertEquals("16e3ccad-aaaa", s.suggestedClientUuid());
        assertEquals(1.0, s.confidence(), 0.0001);
    }

    @Test
    void fuzzyMatch_whenNoExact() {
        ClientService cs = mock(ClientService.class);
        when(cs.findByExactNameIgnoreCase("Vattenfall")).thenReturn(null);
        when(cs.findFuzzyMatch("Vattenfall"))
                .thenReturn(Optional.of(client("2cbb7f5e-bbbb", "VATTENFALL VINDKRAFT A/S")));

        PhantomClientResolver resolver = new PhantomClientResolver(cs);
        PhantomClientSuggestion s = resolver.suggest("Konsulenthonorar Vattenfall");

        assertEquals(SuggestionMethod.FUZZY, s.method());
        assertEquals("2cbb7f5e-bbbb", s.suggestedClientUuid());
        assertTrue(s.confidence() >= 0.9 && s.confidence() <= 1.0);
    }

    @Test
    void noCandidate_givesNone() {
        ClientService cs = mock(ClientService.class);
        when(cs.findByExactNameIgnoreCase(anyString())).thenReturn(null);
        when(cs.findFuzzyMatch(anyString())).thenReturn(Optional.empty());

        PhantomClientResolver resolver = new PhantomClientResolver(cs);
        PhantomClientSuggestion s = resolver.suggest("Magnit Global");

        assertEquals(SuggestionMethod.NONE, s.method());
        assertNull(s.suggestedClientUuid());
    }

    @Test
    void blankLabel_givesNone() {
        ClientService cs = mock(ClientService.class);
        PhantomClientResolver resolver = new PhantomClientResolver(cs);
        assertEquals(SuggestionMethod.NONE, resolver.suggest("   ").method());
        verifyNoInteractions(cs);
    }

    @Test
    void containsMatch_whenNoExactOrFuzzy() {
        ClientService cs = mock(ClientService.class);
        when(cs.findByExactNameIgnoreCase(anyString())).thenReturn(null);
        when(cs.findFuzzyMatch(anyString())).thenReturn(Optional.empty());
        when(cs.findByActiveTrue())
                .thenReturn(List.of(client("c-magnit", "Magnit Global Solutions A/S")));

        PhantomClientResolver resolver = new PhantomClientResolver(cs);
        PhantomClientSuggestion s = resolver.suggest("Magnit");

        assertEquals(SuggestionMethod.CONTAINS, s.method());
        assertEquals("c-magnit", s.suggestedClientUuid());
        assertEquals("Magnit Global Solutions A/S", s.suggestedClientName());
        assertEquals(0.5, s.confidence(), 0.0001);
    }

    @Test
    void containsMatch_isCaseInsensitive_andSkipsNullName() {
        ClientService cs = mock(ClientService.class);
        when(cs.findByExactNameIgnoreCase(anyString())).thenReturn(null);
        when(cs.findFuzzyMatch(anyString())).thenReturn(Optional.empty());
        // First client has a null name (must be skipped, not NPE); second matches case-insensitively.
        when(cs.findByActiveTrue()).thenReturn(List.of(
                client("c-null-name", null),
                client("c-acme", "ACME MAGNIT COMPANY")));

        PhantomClientResolver resolver = new PhantomClientResolver(cs);
        PhantomClientSuggestion s = resolver.suggest("magnit");

        assertEquals(SuggestionMethod.CONTAINS, s.method());
        assertEquals("c-acme", s.suggestedClientUuid());
    }

    @Test
    void noContainsMatch_withPopulatedList_givesNone() {
        ClientService cs = mock(ClientService.class);
        when(cs.findByExactNameIgnoreCase(anyString())).thenReturn(null);
        when(cs.findFuzzyMatch(anyString())).thenReturn(Optional.empty());
        when(cs.findByActiveTrue()).thenReturn(List.of(
                client("c-other", "Other Corp"),
                client("c-another", "Another Inc")));

        PhantomClientResolver resolver = new PhantomClientResolver(cs);
        PhantomClientSuggestion s = resolver.suggest("Magnit");

        assertEquals(SuggestionMethod.NONE, s.method());
        assertNull(s.suggestedClientUuid());
    }

    @Test
    void containsMatch_skipsClientWithNullUuid() {
        ClientService cs = mock(ClientService.class);
        when(cs.findByExactNameIgnoreCase(anyString())).thenReturn(null);
        when(cs.findFuzzyMatch(anyString())).thenReturn(Optional.empty());
        // A name match with a null uuid must NOT be returned as a "found" suggestion.
        when(cs.findByActiveTrue()).thenReturn(List.of(
                client(null, "Magnit Global"),
                client("c-real", "Magnit Holdings")));

        PhantomClientResolver resolver = new PhantomClientResolver(cs);
        PhantomClientSuggestion s = resolver.suggest("Magnit");

        assertEquals(SuggestionMethod.CONTAINS, s.method());
        assertEquals("c-real", s.suggestedClientUuid());
    }
}
