package dk.trustworks.intranet.aggregates.crm.trustlink.services;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The spelling rules every TrustLink comparison rests on. Each case here is a difference
 * that actually exists between the TrustLink list and Intra's user table on
 * 2026-09-13 — the double spaces, the punctuated initial, the degree suffix and the
 * Danish letters typed both ways.
 */
class TrustLinkNameNormalizerTest {

    @Test
    void danishLettersAreSpelledOutInsteadOfHavingTheirAccentStripped() {
        assertEquals("boeggild", TrustLinkNameNormalizer.normalize("Bøggild"));
        assertEquals("soerensen", TrustLinkNameNormalizer.normalize("Sørensen"));
        assertEquals("kjaergaard", TrustLinkNameNormalizer.normalize("Kjærgaard"));
        assertEquals("aase", TrustLinkNameNormalizer.normalize("Åse"));
        assertEquals("mueller", TrustLinkNameNormalizer.normalize("Müller"));
        assertEquals("joerg", TrustLinkNameNormalizer.normalize("Jörg"));
        assertNotEquals(TrustLinkNameNormalizer.normalize("Boggild"),
                TrustLinkNameNormalizer.normalize("Bøggild"),
                "stripping the stroke would make Bøggild and Boggild the same person");
    }

    @Test
    void theSameDanishLetterComposedAndDecomposedNormalisesTheSame() {
        assertEquals("aase", TrustLinkNameNormalizer.normalize("Åse"));
        assertEquals("aase", TrustLinkNameNormalizer.normalize("Åse"),
                "the decomposed spelling is the one macOS hands over, and it must land on the same string");
        assertEquals("andre", TrustLinkNameNormalizer.normalize("André"));
        assertEquals("andre", TrustLinkNameNormalizer.normalize("André"));
    }

    @Test
    void accentsWithNoDanishSpellingConventionAreFoldedAway() {
        assertEquals("andre engsbye", TrustLinkNameNormalizer.normalize("André Engsbye"));
        assertEquals("renee", TrustLinkNameNormalizer.normalize("Renée"));
        assertEquals("nunez", TrustLinkNameNormalizer.normalize("Núñez"));
    }

    @Test
    void doubleSpacesAndPunctuationCollapseToSingleSpaces() {
        assertEquals("tanja boeggild kaufmann", TrustLinkNameNormalizer.normalize("Tanja  Bøggild Kaufmann"));
        assertEquals("andre engsbye rasmussen", TrustLinkNameNormalizer.normalize("André  Engsbye Rasmussen"));
        assertEquals("michelle r cantor", TrustLinkNameNormalizer.normalize("Michelle R. Cantor"));
        assertEquals("hans lassen", TrustLinkNameNormalizer.normalize("  Hans\tLassen\n"));
    }

    @Test
    void titlesAndDegreeSuffixesAreKeptBecauseDroppingTokensIsAMatchingDecision() {
        assertEquals("jp carvalho joao paulo venezian de carvalho mba",
                TrustLinkNameNormalizer.normalize("(JP Carvalho) Joao Paulo Venezian de Carvalho, MBA"));
        assertTrue(TrustLinkNameNormalizer.normalize("Peter Faber, MBA").endsWith(" mba"));
    }

    @Test
    void caseIsFoldedAndDigitsSurvive() {
        assertEquals("akademikerpension", TrustLinkNameNormalizer.normalize("AkademikerPension"));
        assertEquals("novo nordisk a s", TrustLinkNameNormalizer.normalize("NOVO NORDISK A/S"));
        assertEquals("3shape", TrustLinkNameNormalizer.normalize("3Shape"));
    }

    @Test
    void aNameThatIsNullOrOnlyPunctuationNormalisesToNothing() {
        assertEquals("", TrustLinkNameNormalizer.normalize(null));
        assertEquals("", TrustLinkNameNormalizer.normalize("   "));
        assertEquals("", TrustLinkNameNormalizer.normalize("--- ???"));
        assertEquals(List.of(), TrustLinkNameNormalizer.tokens(null));
        assertEquals(List.of(), TrustLinkNameNormalizer.tokens("  "));
    }

    @Test
    void tokensAreTheNormalisedWordsInOrder() {
        assertEquals(List.of("michelle", "r", "cantor"), TrustLinkNameNormalizer.tokens("Michelle R. Cantor"));
        assertEquals(List.of("tanja", "boeggild", "kaufmann"), TrustLinkNameNormalizer.tokens("Tanja  Bøggild Kaufmann"));
        assertEquals(List.of("novo", "nordisk", "a", "s"), TrustLinkNameNormalizer.tokens("Novo Nordisk A/S"));
    }
}
