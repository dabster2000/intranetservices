package dk.trustworks.intranet.aggregates.crm.trustlink.services;

import dk.trustworks.intranet.aggregates.crm.trustlink.model.TrustLinkCompanyAlias;
import dk.trustworks.intranet.aggregates.crm.trustlink.model.enums.AliasSource;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The alias index: which TrustLink company names get queried, and which clients each
 * returned person is written to.
 *
 * <p>Both rules here are ones a reasonable person would get wrong. Collapsing the index to
 * {@code name → one client} reads as an obvious simplification and silently gives a shared
 * company to whichever client was read first. Leaving disabled rows in reads as harmless and
 * quietly undoes the only durable way to say "not this company".
 */
class TrustLinkCompanyAliasIndexTest {

    private static final String NOVO = "11111111-1111-1111-1111-111111111111";
    private static final String OTHER = "22222222-2222-2222-2222-222222222222";

    @Test
    @DisplayName("a client's several TrustLink names all become queryable keys")
    void everyEnabledNameIsAKey() {
        Map<String, List<String>> index = TrustLinkCompanyAliasService.companyIndex(List.of(
                alias(NOVO, "Novo Nordisk", AliasSource.AUTO, true),
                alias(NOVO, "Novo Nordisk A/S", AliasSource.MANUAL, true)));

        assertEquals(2, index.size());
        assertEquals(List.of(NOVO), index.get("Novo Nordisk"));
        assertEquals(List.of(NOVO), index.get("Novo Nordisk A/S"));
    }

    @Test
    @DisplayName("a company two clients both claim fans out to both of them")
    void oneCompanyCanBelongToTwoClients() {
        Map<String, List<String>> index = TrustLinkCompanyAliasService.companyIndex(List.of(
                alias(NOVO, "Novo Nordisk", AliasSource.AUTO, true),
                alias(OTHER, "Novo Nordisk", AliasSource.MANUAL, true)));

        assertEquals(List.of(NOVO, OTHER), index.get("Novo Nordisk"));
    }

    @Test
    @DisplayName("the same client twice on one company does not duplicate the row it would write")
    void aClientIsListedOnce() {
        Map<String, List<String>> index = TrustLinkCompanyAliasService.companyIndex(List.of(
                alias(NOVO, "Novo Nordisk", AliasSource.AUTO, true),
                alias(NOVO, "Novo Nordisk", AliasSource.MANUAL, true)));

        assertEquals(List.of(NOVO), index.get("Novo Nordisk"));
    }

    @Test
    @DisplayName("a suppressed name is never queried, whoever suppressed it")
    void disabledNamesAreDropped() {
        Map<String, List<String>> index = TrustLinkCompanyAliasService.companyIndex(List.of(
                alias(NOVO, "Novo Nordisk", AliasSource.AUTO, true),
                alias(NOVO, "Novozymes", AliasSource.MANUAL, false)));

        assertTrue(index.containsKey("Novo Nordisk"));
        assertFalse(index.containsKey("Novozymes"));
    }

    @Test
    @DisplayName("case is preserved, because the TrustLink search is case-sensitive")
    void namesAreNotFolded() {
        Map<String, List<String>> index = TrustLinkCompanyAliasService.companyIndex(List.of(
                alias(NOVO, "NOVO NORDISK A/S", AliasSource.MANUAL, true)));

        assertTrue(index.containsKey("NOVO NORDISK A/S"));
        assertFalse(index.containsKey("novo nordisk a/s"));
    }

    private static TrustLinkCompanyAlias alias(String clientUuid, String companyName,
                                               AliasSource source, boolean enabled) {
        TrustLinkCompanyAlias row = new TrustLinkCompanyAlias();
        row.setClientUuid(clientUuid);
        row.setCompanyName(companyName);
        row.setSource(source);
        row.setEnabled(enabled);
        return row;
    }
}
