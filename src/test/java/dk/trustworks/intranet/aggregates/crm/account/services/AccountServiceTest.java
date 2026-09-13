package dk.trustworks.intranet.aggregates.crm.account.services;

import dk.trustworks.intranet.aggregates.crm.account.model.enums.AccountBand;
import jakarta.ws.rs.WebApplicationException;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * The hand-rolled normalisation in {@link AccountService}.
 *
 * <p>Written as plain Java rather than bean-validation annotations because bean validation
 * is NOT active in this build — {@code quarkus-hibernate-validator} is absent, so every
 * {@code @NotBlank} would be inert decoration. These are the checks that actually run.
 *
 * <p>Fast tier: no Quarkus boot, no database. The methods that need one — {@code patch},
 * {@code replaceSupportedBy}, {@code replaceDomains} — are exercised against the local DB
 * during verification; what is locked here is the logic that decides what reaches a column.
 */
class AccountServiceTest {

    // ------------------------------------------------------------------------
    // Domains — the join that decides which account a meeting lands on
    // ------------------------------------------------------------------------

    @Test
    void domainsAreNormalisedFromEveryShapeSomebodyMightType() {
        assertEquals("acme.dk", AccountService.normaliseDomain("acme.dk"));
        assertEquals("acme.dk", AccountService.normaliseDomain("  ACME.DK  "));
        assertEquals("acme.dk", AccountService.normaliseDomain("@acme.dk"));
        assertEquals("acme.dk", AccountService.normaliseDomain("someone@acme.dk"));
        assertEquals("acme.dk", AccountService.normaliseDomain("https://acme.dk"));
        assertEquals("acme.dk", AccountService.normaliseDomain("https://www.acme.dk/about"));
        assertEquals("acme.dk", AccountService.normaliseDomain("www.acme.dk"));
    }

    /** A sub-domain is a different domain and must not be flattened to its parent. */
    @Test
    void subdomainsAreKeptDistinct() {
        assertEquals("it.acme.dk", AccountService.normaliseDomain("it.acme.dk"));
    }

    @Test
    void nonDomainsAreRejectedRatherThanStored() {
        assertNull(AccountService.normaliseDomain(null));
        assertNull(AccountService.normaliseDomain(""));
        assertNull(AccountService.normaliseDomain("   "));
        assertNull(AccountService.normaliseDomain("acme"), "no dot is not a domain");
        assertNull(AccountService.normaliseDomain("acme .dk"), "a space is not a domain");
        assertNull(AccountService.normaliseDomain(".acme.dk"));
        assertNull(AccountService.normaliseDomain("acme.dk."));
    }

    /**
     * The whole point of the deny-list. A meeting with someone on gmail.com says nothing
     * about which account it belongs to, and claiming trustworks.dk would attribute every
     * internal meeting in the firm to one client.
     */
    @Test
    void sharedAndInternalDomainsAreRefusedWithAnExplanation() {
        WebApplicationException freemail =
                assertThrows(WebApplicationException.class, () -> AccountService.normaliseDomain("gmail.com"));
        assertEquals(400, freemail.getResponse().getStatus());

        assertThrows(WebApplicationException.class, () -> AccountService.normaliseDomain("trustworks.dk"));
        assertThrows(WebApplicationException.class, () -> AccountService.normaliseDomain("someone@hotmail.dk"));
        // Case and shape must not be a way round it.
        assertThrows(WebApplicationException.class, () -> AccountService.normaliseDomain("  GMAIL.COM "));
    }

    @Test
    void overlongDomainsAreRejectedRatherThanTruncatedIntoADifferentDomain() {
        String tooLong = "a".repeat(AccountService.MAX_DOMAIN_CHARS) + ".dk";
        assertNull(AccountService.normaliseDomain(tooLong));
    }

    // ------------------------------------------------------------------------
    // Slack space
    // ------------------------------------------------------------------------

    @Test
    void slackSpaceIsStoredWithoutItsHash() {
        assertEquals("a_oersted", AccountService.normaliseSlackSpace("#a_oersted"));
        assertEquals("a_oersted", AccountService.normaliseSlackSpace("a_oersted"));
        assertEquals("a_oersted", AccountService.normaliseSlackSpace("  ##a_oersted  "));
        assertNull(AccountService.normaliseSlackSpace("#"));
        assertNull(AccountService.normaliseSlackSpace("   "));
    }

    // ------------------------------------------------------------------------
    // Band
    // ------------------------------------------------------------------------

    @Test
    void bandsParseCaseInsensitively() {
        assertEquals(AccountBand.STRATEGIC, AccountService.parseBand("STRATEGIC"));
        assertEquals(AccountBand.ACTIVE, AccountService.parseBand(" active "));
        assertEquals(AccountBand.BACKLOG, AccountService.parseBand("Backlog"));
    }

    /**
     * Unlike a signal's TYPE — where an unrecognised value must never cost somebody their
     * capture — an unknown band is a caller bug. Guessing would quietly move an account.
     */
    @Test
    void anUnknownBandIsRefusedRatherThanGuessed() {
        WebApplicationException error =
                assertThrows(WebApplicationException.class, () -> AccountService.parseBand("KEY_ACCOUNT"));
        assertEquals(400, error.getResponse().getStatus());
    }

    // ------------------------------------------------------------------------
    // Trimming
    // ------------------------------------------------------------------------

    @Test
    void blankTextBecomesNullSoAnEmptyFieldReadsAsAbsent() {
        assertNull(AccountService.trimToNull("", 10));
        assertNull(AccountService.trimToNull("   ", 10));
        assertNull(AccountService.trimToNull(null, 10));
        assertEquals("hello", AccountService.trimToNull("  hello  ", 10));
    }

    @Test
    void textLongerThanTheColumnIsTruncatedRatherThanRejected() {
        assertEquals("abcde", AccountService.trimToNull("abcdefghij", 5));
    }
}
