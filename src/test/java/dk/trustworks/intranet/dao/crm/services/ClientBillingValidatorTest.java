package dk.trustworks.intranet.dao.crm.services;

import dk.trustworks.intranet.dao.crm.model.Client;
import dk.trustworks.intranet.dao.crm.model.enums.ClientType;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * What a row has to carry before it can be billed — and what a PROSPECT is deliberately
 * not asked for (relationships spec §2.2, §2.4).
 *
 * <p>Two gates use these rules: the client form, and {@code ContractService.save} when a
 * prospect's first contract makes it a customer. They have to be the SAME rules, or a
 * company passes one and fails the other — and the worse direction is the quiet one, where
 * a row reaches billing without a CVR because the gate that asked was skipped.
 *
 * <p>Fast tier: pure static methods, no Quarkus, no database.
 */
class ClientBillingValidatorTest {

    private static Client client(String name, String country, String cvr) {
        Client client = new Client();
        client.setName(name);
        client.setBillingCountry(country);
        client.setCvr(cvr);
        return client;
    }

    // ------------------------------------------------------------------------
    // Format — asked of everybody, prospect or not
    // ------------------------------------------------------------------------

    @Test
    void aNameOfFewerThanTwoCharactersIsRefused() {
        assertNotNull(ClientBillingValidator.formatProblem(client("D", "DK", null)));
        assertNotNull(ClientBillingValidator.formatProblem(client("  ", "DK", null)));
        assertNull(ClientBillingValidator.formatProblem(client("DSB", "DK", null)));
    }

    @Test
    void aMalformedCvrIsRefusedEvenWhenItIsOptional() {
        // A prospect is not ASKED for a CVR, but a wrong one is wrong whoever typed it —
        // and storing it would mean the graduation gate later accepts eight junk digits.
        assertEquals("CVR must be exactly 8 digits",
                ClientBillingValidator.formatProblem(client("DSB", "DK", "1234")));
        assertNull(ClientBillingValidator.formatProblem(client("DSB", "DK", "12345678")));
    }

    @Test
    void aForeignRegistrationNumberIsJudgedByTheForeignFormat() {
        // Eight digits is the Danish shape and nobody else's. A Swedish organisationsnummer
        // has a hyphen and ten digits; refusing it as "not exactly 8 digits" would make the
        // registration number impossible to fill in for the very clients it was extended to.
        assertNull(ClientBillingValidator.formatProblem(client("Ørsted Sverige", "SE", "556016-0680")));
        assertNull(ClientBillingValidator.formatProblem(client("Beispiel GmbH", "DE", "HRB 12345")));
        assertNull(ClientBillingValidator.formatProblem(client("Voorbeeld BV", "NL", "12345678")));
    }

    @Test
    void aForeignRegistrationNumberTooShortOrTooLongIsRefused() {
        // The ceiling is client.cvr, varchar(20): a longer value is truncated on the way in
        // and then never matches the registry again.
        assertNotNull(ClientBillingValidator.formatProblem(client("Kort AB", "SE", "12")));
        assertNotNull(ClientBillingValidator.formatProblem(
                client("Lang AB", "SE", "123456789012345678901")));
        assertNotNull(ClientBillingValidator.formatProblem(client("Tegn AB", "SE", "556016_0680")));
    }

    @Test
    void aBlankCountryIsReadAsDanishSoTheStrictFormatApplies() {
        // ContractService.graduateProspect validates a row straight out of the database,
        // where the country may never have been set. Falling through to the lax foreign
        // format there would let junk into the one gate that stands between a prospect and
        // an invoice.
        assertEquals("CVR must be exactly 8 digits",
                ClientBillingValidator.formatProblem(client("DSB", null, "1234")));
        assertEquals("CVR is required",
                ClientBillingValidator.billingProblem(client("DSB", null, null)));
    }

    @Test
    void aMalformedCountryOrCurrencyIsRefused() {
        Client badCountry = client("DSB", "Denmark", null);
        assertEquals("Invalid country code", ClientBillingValidator.formatProblem(badCountry));

        Client badCurrency = client("DSB", "DK", null);
        badCurrency.setCurrency("KRONER");
        assertEquals("Invalid currency code", ClientBillingValidator.formatProblem(badCurrency));
    }

    // ------------------------------------------------------------------------
    // Billing completeness — asked only when the row can be billed
    // ------------------------------------------------------------------------

    @Test
    void aDanishBillingClientNeedsACvr() {
        assertEquals("CVR is required",
                ClientBillingValidator.billingProblem(client("DSB", "DK", null)));
        assertNull(ClientBillingValidator.billingProblem(client("DSB", "DK", "12345678")));
    }

    /**
     * The country dropdown is not a way past the rule.
     *
     * <p>This test asserted the opposite until 2026-09-14: a client outside Denmark needed
     * no registration number at all, so picking SE was enough to create a customer
     * identified by nothing but a name somebody typed. The reason for the rule — an invoiced
     * company is one that exists in a public registry — does not stop at the border, so the
     * country now decides the FORMAT of the number and never whether it is asked for.
     */
    @Test
    void aNonDanishBillingClientNeedsItsOwnRegistrationNumber() {
        assertEquals("A company registration number is required for clients outside Denmark",
                ClientBillingValidator.billingProblem(client("Ørsted Sverige", "SE", null)));
        assertNull(ClientBillingValidator.billingProblem(client("Ørsted Sverige", "SE", "556016-0680")));
    }

    /**
     * The whole point of the split.
     *
     * <p>Requiring a CVR to write down that somebody had a coffee with a company is what
     * stopped people writing it down at all: production gained 126 client rows in 2026
     * against never more than 13 a year before, in batches of ten and twelve, with no CVR
     * and no owner — alongside "Unnamed Client" and "DELETE THIS - Bording Group".
     */
    @Test
    void aProspectPassesTheFormatGateWithNoBillingDetailsAtAll() {
        Client prospect = client("DSB", "DK", null);
        prospect.setType(ClientType.PROSPECT);

        assertNull(ClientBillingValidator.formatProblem(prospect));
        // …and the billing gate still refuses it, which is what graduation runs.
        assertTrue(ClientBillingValidator.billingProblem(prospect).contains("CVR is required"));
    }

    /**
     * Both gates read one method, so they cannot drift apart.
     *
     * <p>The dangerous drift is the quiet direction: a row that passes the client form and
     * is therefore never asked again reaching an invoice without a registration number. The
     * form and {@code ContractService.graduateProspect} both call
     * {@link ClientBillingValidator#billingProblem}, so the assertion that matters is simply
     * that one method answers — there is no second implementation to compare it against.
     */
    @Test
    void theSameAnswerServesTheFormAndTheGraduationGate() {
        Client foreignWithoutNumber = client("Example Ltd", "GB", null);
        assertNotNull(ClientBillingValidator.billingProblem(foreignWithoutNumber));

        foreignWithoutNumber.setCvr("SC123456");
        assertNull(ClientBillingValidator.billingProblem(foreignWithoutNumber));
    }
}
