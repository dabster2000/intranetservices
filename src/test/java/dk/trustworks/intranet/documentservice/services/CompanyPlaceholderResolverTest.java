package dk.trustworks.intranet.documentservice.services;

import dk.trustworks.intranet.documentservice.model.enums.CompanyFactKey;
import dk.trustworks.intranet.documentservice.model.enums.DataSource;
import dk.trustworks.intranet.documentservice.services.CompanyPlaceholderResolver.MissingCompanyFactException;
import dk.trustworks.intranet.documentservice.services.CompanyPlaceholderResolver.PlaceholderRef;
import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The fail-closed contract of company-fact resolution (spec §4.9): an
 * explicitly mapped fact MUST resolve or the prepare is refused naming
 * the fact; legacy placeholders (no source_field) resolve best-effort
 * and never break a pre-facts template.
 */
class CompanyPlaceholderResolverTest {

    private static final Map<String, String> FACTS = Map.of(
            "LEGAL_NAME", "Trustworks Technology ApS",
            "NAME_GENITIVE", "Trustworks Technologys",
            "CVR", "12345678");

    // ---- mergeCompanyValues -----------------------------------------------------

    @Test
    void explicitSourceFieldResolvesFromFacts_andWinsOverClientValue() {
        Map<String, String> values = new HashMap<>(Map.of("COMPANY_NAME_GEN", "stale client value"));
        CompanyPlaceholderResolver.mergeCompanyValues(
                List.of(new PlaceholderRef("COMPANY_NAME_GEN", DataSource.COMPANY, "NAME_GENITIVE")),
                FACTS, "Trustworks Technology ApS", values);
        assertEquals("Trustworks Technologys", values.get("COMPANY_NAME_GEN"));
    }

    @Test
    void explicitSourceFieldMissingFactFailsClosed_namingFactAndSettings() {
        Map<String, String> values = new HashMap<>();
        MissingCompanyFactException e = assertThrows(MissingCompanyFactException.class, () ->
                CompanyPlaceholderResolver.mergeCompanyValues(
                        List.of(new PlaceholderRef("COMPANY_LUNCH", DataSource.COMPANY, "LUNCH_PRICE")),
                        FACTS, "Trustworks Technology ApS", values));
        assertEquals(List.of("LUNCH_PRICE"), e.getMissingFactKeys());
        assertTrue(e.getMessage().contains("LUNCH_PRICE"));
        assertTrue(e.getMessage().contains("Trustworks Technology ApS"));
        assertTrue(e.getMessage().contains("Settings → Selskaber"));
        // Nothing is merged on failure — the map is untouched.
        assertTrue(values.isEmpty());
    }

    @Test
    void legacyPlaceholderResolvesViaKeyDerivedFact() {
        Map<String, String> values = new HashMap<>();
        CompanyPlaceholderResolver.mergeCompanyValues(
                List.of(new PlaceholderRef("COMPANY_CVR", DataSource.COMPANY, null)),
                FACTS, "TW", values);
        assertEquals("12345678", values.get("COMPANY_CVR"));
    }

    @Test
    void legacyPlaceholderWithoutMatchingFactKeepsClientValue_neverFails() {
        Map<String, String> values = new HashMap<>(Map.of("COMPANY_SOMETHING_ODD", "typed by preparer"));
        CompanyPlaceholderResolver.mergeCompanyValues(
                List.of(new PlaceholderRef("COMPANY_SOMETHING_ODD", DataSource.COMPANY, null)),
                FACTS, "TW", values);
        assertEquals("typed by preparer", values.get("COMPANY_SOMETHING_ODD"));
    }

    @Test
    void legacyNameAliasResolvesLegalName() {
        Map<String, String> values = new HashMap<>();
        CompanyPlaceholderResolver.mergeCompanyValues(
                List.of(new PlaceholderRef("COMPANY_NAME", DataSource.COMPANY, null)),
                FACTS, "TW", values);
        assertEquals("Trustworks Technology ApS", values.get("COMPANY_NAME"));
    }

    @Test
    void nonCompanyPlaceholdersAreLeftAlone() {
        Map<String, String> values = new HashMap<>(Map.of("EMPLOYEE_NAME", "Hans"));
        CompanyPlaceholderResolver.mergeCompanyValues(
                List.of(new PlaceholderRef("EMPLOYEE_NAME", DataSource.USER, "NAME")),
                FACTS, "TW", values);
        assertEquals("Hans", values.get("EMPLOYEE_NAME"));
        assertEquals(1, values.size());
    }

    @Test
    void allMissingFactsAreReportedTogether() {
        MissingCompanyFactException e = assertThrows(MissingCompanyFactException.class, () ->
                CompanyPlaceholderResolver.mergeCompanyValues(
                        List.of(new PlaceholderRef("A", DataSource.COMPANY, "LUNCH_PRICE"),
                                new PlaceholderRef("B", DataSource.COMPANY, "PENSION_PROVIDER")),
                        FACTS, "TW", new HashMap<>()));
        assertEquals(List.of("LUNCH_PRICE", "PENSION_PROVIDER"), e.getMissingFactKeys());
    }

    // ---- substituteCompanyTokens ------------------------------------------------

    /**
     * The {@code ${COMPANY_*}} substitution is generic over the fact vocabulary —
     * it is not tied to any one key. (It is deliberately not exercised here with a
     * counter-signer: signers live in {@code template_default_signers}, not in facts.)
     */
    @Test
    void companyTokensResolveFromFacts() {
        assertEquals("Trustworks Technology ApS",
                CompanyPlaceholderResolver.substituteCompanyTokens("${COMPANY_LEGAL_NAME}", FACTS));
        assertEquals("Trustworks Technology ApS (CVR 12345678)",
                CompanyPlaceholderResolver.substituteCompanyTokens(
                        "${COMPANY_LEGAL_NAME} (CVR ${COMPANY_CVR})", FACTS));
    }

    @Test
    void unknownTokenIsLeftUntouchedForTheFailClosedGuard() {
        assertEquals("${COMPANY_UNKNOWN_FACT}",
                CompanyPlaceholderResolver.substituteCompanyTokens("${COMPANY_UNKNOWN_FACT}", FACTS));
    }

    @Test
    void nonCompanyTokensAreNotTouched() {
        assertEquals("${EMPLOYEE_NAME}",
                CompanyPlaceholderResolver.substituteCompanyTokens("${EMPLOYEE_NAME}", FACTS));
    }

    // ---- CompanyFactKey helpers -------------------------------------------------

    @Test
    void factKeyDerivationStripsPrefixAndAppliesAliases() {
        assertEquals("LEGAL_NAME", CompanyFactKey.factKeyForPlaceholder("COMPANY_NAME").orElseThrow());
        assertEquals("LEGAL_NAME", CompanyFactKey.factKeyForPlaceholder("COMPANY_NAVN").orElseThrow());
        assertEquals("ADDRESS", CompanyFactKey.factKeyForPlaceholder("COMPANY_ADRESSE").orElseThrow());
        assertEquals("CVR", CompanyFactKey.factKeyForPlaceholder("COMPANY_CVR").orElseThrow());
        assertEquals("NAME_GENITIVE", CompanyFactKey.factKeyForPlaceholder("COMPANY_NAME_GENITIVE").orElseThrow());
        assertEquals("PENSION_PROVIDER", CompanyFactKey.factKeyForPlaceholder("PENSION_PROVIDER").orElseThrow());
    }

    @Test
    void factKeyValidation() {
        assertTrue(CompanyFactKey.isValidKey("PENSION_PROVIDER"));
        assertTrue(CompanyFactKey.isValidKey("X1"));
        assertFalse(CompanyFactKey.isValidKey("pension"));
        assertFalse(CompanyFactKey.isValidKey("1BAD"));
        assertFalse(CompanyFactKey.isValidKey(""));
        assertFalse(CompanyFactKey.isValidKey(null));
        assertFalse(CompanyFactKey.isValidKey("HAS SPACE"));
    }
}
