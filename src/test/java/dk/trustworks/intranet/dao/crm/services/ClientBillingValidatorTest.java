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
        assertEquals("CVR is required for Danish clients",
                ClientBillingValidator.billingProblem(client("DSB", "DK", null)));
        assertNull(ClientBillingValidator.billingProblem(client("DSB", "DK", "12345678")));
    }

    @Test
    void aNonDanishBillingClientDoesNotNeedACvr() {
        assertNull(ClientBillingValidator.billingProblem(client("Ørsted Sverige", "SE", null)));
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
}
