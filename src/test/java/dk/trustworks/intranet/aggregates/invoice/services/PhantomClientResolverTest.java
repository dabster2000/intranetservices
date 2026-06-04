package dk.trustworks.intranet.aggregates.invoice.services;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

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
}
