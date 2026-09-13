package dk.trustworks.intranet.aggregates.crm.trustlink.services;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.Locale;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;

/**
 * The case collision that killed the first real sync run, pinned so it cannot come back.
 *
 * <p>Two true things about a TrustLink company name disagree:
 *
 * <ul>
 *   <li>the upstream search is case-SENSITIVE, so a stored name must keep TrustLink's exact
 *       spelling or it matches nobody;</li>
 *   <li>{@code trustlink_company_alias.company_name} is {@code utf8mb4_general_ci}, so two
 *       spellings differing only in case are ONE row to
 *       {@code uq_trustlink_alias_client_company}.</li>
 * </ul>
 *
 * <p>Intra calls a real client {@code Styrelsen for IT og Læring}. TrustLink calls the
 * company {@code Styrelsen for It og Læring}. The seeder added the client's own name and the
 * typeahead's answer, Java's {@code equals} saw two names, MariaDB's unique key saw one, and
 * the resulting {@code Duplicate entry} rolled back the seeding transaction and failed the
 * whole nightly run — for all 294 clients, not just this one. The job would have ingested
 * nothing, every night, with nothing on any page to say why.
 *
 * <p>These are the two halves of the fix. Neither is sufficient alone: folding without
 * preferring upstream's spelling stores a name that returns no connections, and preferring
 * upstream's spelling without folding still writes two rows.
 */
class TrustLinkAliasCaseCollisionTest {

    /** The exact pair from the failed run, kept verbatim rather than reduced to "a b". */
    private static final String INTRA_SPELLING = "Styrelsen for IT og Læring";
    private static final String TRUSTLINK_SPELLING = "Styrelsen for It og Læring";

    @Test
    @DisplayName("the two real spellings are different strings to Java")
    void theCollisionIsRealAndNotAnArtefactOfTheTest() {
        assertNotEquals(INTRA_SPELLING, TRUSTLINK_SPELLING,
                "if these were equal the bug could not have happened and this test proves nothing");
    }

    @Test
    @DisplayName("but one row to the unique key, so de-duplication must fold them together")
    void foldingCollapsesThemTheWayTheDatabaseDoes() {
        assertEquals(TrustLinkCompanyAliasService.foldForUniqueKey(INTRA_SPELLING),
                TrustLinkCompanyAliasService.foldForUniqueKey(TRUSTLINK_SPELLING));
    }

    @Test
    @DisplayName("folding trims, so a stray space cannot smuggle a duplicate past the check")
    void foldingTrims() {
        assertEquals(TrustLinkCompanyAliasService.foldForUniqueKey("Novo Nordisk"),
                TrustLinkCompanyAliasService.foldForUniqueKey("  Novo Nordisk  "));
    }

    @Test
    @DisplayName("a null or blank name folds to empty rather than throwing")
    void foldingIsTotal() {
        assertEquals("", TrustLinkCompanyAliasService.foldForUniqueKey(null));
        assertEquals("", TrustLinkCompanyAliasService.foldForUniqueKey("   "));
    }

    @Test
    @DisplayName("genuinely different companies still fold apart — folding must not over-merge")
    void foldingDoesNotCollapseDistinctCompanies() {
        assertNotEquals(TrustLinkCompanyAliasService.foldForUniqueKey("Novo Nordisk"),
                TrustLinkCompanyAliasService.foldForUniqueKey("Novo Nordisk A/S"));
        assertNotEquals(TrustLinkCompanyAliasService.foldForUniqueKey("Novo Nordisk"),
                TrustLinkCompanyAliasService.foldForUniqueKey("Novonesis"));
    }

    /**
     * The folding is deliberately {@code Locale.ROOT}. Under a Turkish default locale
     * {@code "IT".toLowerCase()} is {@code "ıt"}, which would NOT equal {@code "it"} — and the
     * collision this whole class exists to prevent would come straight back, on exactly the
     * client that first hit it.
     */
    @Test
    @DisplayName("folding is locale-independent — a Turkish default locale must not resurrect the bug")
    void foldingIsLocaleIndependent() {
        Locale original = Locale.getDefault();
        try {
            Locale.setDefault(Locale.forLanguageTag("tr"));
            assertEquals(TrustLinkCompanyAliasService.foldForUniqueKey(INTRA_SPELLING),
                    TrustLinkCompanyAliasService.foldForUniqueKey(TRUSTLINK_SPELLING),
                    "Locale.ROOT is what keeps the Turkish dotless i out of this");
        } finally {
            Locale.setDefault(original);
        }
    }
}
