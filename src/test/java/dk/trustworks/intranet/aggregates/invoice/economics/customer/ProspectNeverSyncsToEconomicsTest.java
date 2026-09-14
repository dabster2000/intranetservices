package dk.trustworks.intranet.aggregates.invoice.economics.customer;

import dk.trustworks.intranet.dao.crm.model.enums.ClientType;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * A prospect is never an e-conomic customer (relationships spec §2.2).
 *
 * <p>Before the type existed, {@code ClientResource.save} pushed every new client to every
 * configured agreement, so a company somebody had a coffee with became a customer in both
 * e-conomic agreements the same minute it was written down. That is what made writing one
 * down a bookkeeping act, and it is the reason the client form was the only door.
 *
 * <p>The two callers gate on the type before reaching {@link AgreementDefaults}; this throw
 * is the backstop that makes "no path can sync one by accident" TRUE rather than merely
 * intended, and this test is what keeps the backstop from being quietly removed as dead
 * code by somebody who cannot see the two call sites from here.
 */
class ProspectNeverSyncsToEconomicsTest {

    private static final AgreementDefaults DEFAULTS =
            new AgreementDefaults(1, 2, "DKK", 0, 4);

    @Test
    void aClientAndAPartnerGetTheirOwnCustomerGroups() {
        assertEquals(1, DEFAULTS.groupNumberFor(ClientType.CLIENT));
        assertEquals(2, DEFAULTS.groupNumberFor(ClientType.PARTNER));
    }

    @Test
    void aProspectIsRefusedOutrightRatherThanFallingThroughToTheClientGroup() {
        IllegalArgumentException thrown =
                assertThrows(IllegalArgumentException.class, () -> DEFAULTS.groupNumberFor(ClientType.PROSPECT));
        assertTrue(thrown.getMessage().contains("first contract"),
                "the refusal has to say WHERE the customer is created instead: " + thrown.getMessage());
    }

    /**
     * A null type used to mean CLIENT, and it still does — the ternary this replaced read
     * {@code type == PARTNER ? partner : client}. Keeping that is what stops a row with no
     * type from becoming an unsyncable prospect by accident.
     */
    @Test
    void anAbsentTypeIsStillTreatedAsAClient() {
        assertEquals(1, DEFAULTS.groupNumberFor(null));
    }
}
