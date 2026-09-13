package dk.trustworks.intranet.aggregates.crm.trustlink.services;

import org.junit.jupiter.api.Test;

import java.util.Arrays;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Which TrustLink company names the nightly seeder may claim for a client without asking
 * anybody — and which ones it must leave for a human to confirm.
 *
 * <p>The pairs here are real: the Intra client is {@code "NOVO NORDISK A/S"} while the 203
 * tier-5 connections sit under the plain {@code "Novo Nordisk"}, and {@code "Arriva"} sits
 * next to {@code "Arriva Danmark"} in the same typeahead. The first pair must match or the
 * feature loses almost every connection it exists to show; the second must not, because
 * the rule that would join it joins every unrelated {@code "X Danmark"} to its parent as
 * well.
 */
class TrustLinkCompanyAliasMatchTest {

    @Test
    void caseAndLegalFormDifferencesAreTheSameCompany() {
        assertTrue(TrustLinkCompanyMatcher.isSameCompany("NOVO NORDISK A/S", "Novo Nordisk A/S"));
        assertTrue(TrustLinkCompanyMatcher.isSameCompany("HEMPEL A/S", "Hempel A/S"));
        assertTrue(TrustLinkCompanyMatcher.isSameCompany("Akademikerpension", "AkademikerPension"));
        assertTrue(TrustLinkCompanyMatcher.isSameCompany("Bankdata I/S", "Bankdata"));
        assertTrue(TrustLinkCompanyMatcher.isSameCompany("Trifork ApS", "Trifork"));
    }

    @Test
    void theShortNameTheConnectionsActuallySitUnderIsTheSameCompany() {
        assertTrue(TrustLinkCompanyMatcher.isSameCompany("NOVO NORDISK A/S", "Novo Nordisk"),
                "203 of the 205 tier-5 connections are filed under the short name");
        assertEquals("novo nordisk", TrustLinkCompanyMatcher.normalizeCompany("NOVO NORDISK A/S"));
    }

    @Test
    void holdingAndGroupDescribeHowTheSameCompanyIsIncorporated() {
        assertTrue(TrustLinkCompanyMatcher.isSameCompany("Hempel", "Hempel Group"));
        assertTrue(TrustLinkCompanyMatcher.isSameCompany("Lundbeck Holding A/S", "Lundbeck"));
    }

    @Test
    void aDanishSubsidiaryIsNotAutomaticallyItsParent() {
        assertFalse(TrustLinkCompanyMatcher.isSameCompany("Arriva", "Arriva Danmark"),
                "\"X Danmark\" is normally a separate legal entity, and the seeder writes rows nobody reviews");
        assertFalse(TrustLinkCompanyMatcher.isSameCompany("Arriva", "Arriva Danmark A/S"));
        assertFalse(TrustLinkCompanyMatcher.isSameCompany("Vattenfall", "Vattenfall Denmark"));
        assertFalse(TrustLinkCompanyMatcher.isSameCompany("Atea", "Atea DK"));
    }

    @Test
    void aDanishSubsidiaryIsStillOfferedToThePersonEditingTheAliases() {
        assertTrue(TrustLinkCompanyMatcher.isRelatedCompany("Arriva", "Arriva Danmark"),
                "for Arriva the two names are the same people — one click, not a silent claim");
        assertTrue(TrustLinkCompanyMatcher.isRelatedCompany("Arriva", "Arriva Danmark A/S"));
        assertTrue(TrustLinkCompanyMatcher.isRelatedCompany("Atea", "Atea DK"));
        assertFalse(TrustLinkCompanyMatcher.isRelatedCompany("Arriva", "Movia"),
                "the generous tier is still a comparison, not a suggestion of everything");
    }

    @Test
    void companiesThatMerelyShareAWordAreDifferentCompanies() {
        assertFalse(TrustLinkCompanyMatcher.isSameCompany("Novo Nordisk", "Novo Nordisk Foundation"));
        assertFalse(TrustLinkCompanyMatcher.isSameCompany("Novo Nordisk", "Novo Holdings"));
        assertFalse(TrustLinkCompanyMatcher.isSameCompany("Novo Nordisk", "Novonesis"));
        assertFalse(TrustLinkCompanyMatcher.isSameCompany("Danske Bank", "Danske Commodities"));
        assertFalse(TrustLinkCompanyMatcher.isRelatedCompany("Novo Nordisk", "Novo Nordisk Foundation"));
    }

    @Test
    void aNameMadeOnlyOfSuffixesKeepsItsWordsInsteadOfCollapsingToNothing() {
        assertFalse(TrustLinkCompanyMatcher.isSameCompany("Group", "Holding"));
        assertFalse(TrustLinkCompanyMatcher.isSameCompany("Danmark", "Denmark"));
        assertEquals("group", TrustLinkCompanyMatcher.normalizeCompany("Group"));
        assertEquals("danmark", TrustLinkCompanyMatcher.normalizeCompanyIgnoringGeography("Danmark"));
    }

    @Test
    void aCompanyWithNoNameIsNeverAMatch() {
        assertFalse(TrustLinkCompanyMatcher.isSameCompany(null, null));
        assertFalse(TrustLinkCompanyMatcher.isSameCompany("", ""));
        assertFalse(TrustLinkCompanyMatcher.isSameCompany("  ", "Novo Nordisk"));
        assertFalse(TrustLinkCompanyMatcher.isSameCompany("???", "---"));
        assertFalse(TrustLinkCompanyMatcher.isRelatedCompany(null, "Arriva"));
    }

    @Test
    void bothFragmentsOfTheSameCompanyAreSelectedFromTheTypeaheadInOrder() {
        List<String> candidates = Arrays.asList(
                "Novo Nordisk", "Novonesis", "Novo Nordisk Foundation", "Novo Nordisk A/S", "Novo Nordisk", null, "  ");

        assertEquals(List.of("Novo Nordisk", "Novo Nordisk A/S"),
                TrustLinkCompanyMatcher.selectMatches("NOVO NORDISK A/S", candidates),
                "a client points at several TrustLink names because TrustLink fragments companies");
    }

    @Test
    void selectingFromNothingIsEmptyRatherThanNull() {
        assertEquals(List.of(), TrustLinkCompanyMatcher.selectMatches("Novo Nordisk", null));
        assertEquals(List.of(), TrustLinkCompanyMatcher.selectMatches("Novo Nordisk", List.of()));
        assertEquals(List.of(), TrustLinkCompanyMatcher.selectMatches("Novo Nordisk", List.of("Novonesis")));
    }
}
