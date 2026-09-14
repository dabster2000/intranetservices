package dk.trustworks.intranet.aggregates.crm.merge;

import dk.trustworks.intranet.aggregates.crm.merge.ClientMergeRules.AccountFrom;
import dk.trustworks.intranet.dao.crm.model.enums.ClientType;
import jakarta.ws.rs.WebApplicationException;
import org.junit.jupiter.api.Test;

import java.time.LocalDateTime;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The spec's §9 decisions, pinned in the fast tier that gates every deploy. The merge
 * itself is exercised against a database by {@code ClientMergeServiceIntegrationTest};
 * what is locked here is what it decides before touching one.
 */
class ClientMergeRulesTest {

    // ---- D4: only a prospect can be merged away ----------------------------------

    @Test
    void aProspectCanBeMergedIntoAnything() {
        assertNull(ClientMergeRules.directionProblem("Devoteam", ClientType.CLIENT, ClientType.PROSPECT));
        assertNull(ClientMergeRules.directionProblem("Devoteam", ClientType.PARTNER, ClientType.PROSPECT));
        assertNull(ClientMergeRules.directionProblem("Devoteam", ClientType.PROSPECT, ClientType.PROSPECT));
    }

    /**
     * Nærpension is deliberately two rows with one CVR — a customer and the intermediary
     * another customer is billed through — and the tool cannot tell that pair from a
     * duplicate. A billed row keeps its own row, whatever the survivor is.
     */
    @Test
    void aCustomerOrAPartnerIsNeverMergedAway() {
        String customer = ClientMergeRules.directionProblem("Nærpension", ClientType.PARTNER, ClientType.CLIENT);
        assertNotNull(customer);
        assertTrue(customer.contains("Nærpension is a customer"), customer);

        String partner = ClientMergeRules.directionProblem("Arba Security ApS", ClientType.CLIENT, ClientType.PARTNER);
        assertNotNull(partner);
        assertTrue(partner.contains("Arba Security ApS is a partner"), partner);
    }

    @Test
    void aRowIsNotMergedIntoItself() {
        assertNotNull(ClientMergeRules.sameRowProblem("a", "a"));
        assertNull(ClientMergeRules.sameRowProblem("a", "b"));
    }

    // ---- D3: the month control with content wins, the winner on a tie -------------

    @Test
    void aMonthControlHasContentWhenApprovedOrAnnotated() {
        assertTrue(ClientMergeRules.monthControlHasContent(LocalDateTime.of(2026, 9, 1, 8, 0), null));
        assertTrue(ClientMergeRules.monthControlHasContent(null, "Invoice held until PO arrives"));
        assertFalse(ClientMergeRules.monthControlHasContent(null, null));
        assertFalse(ClientMergeRules.monthControlHasContent(null, "   "));
    }

    @Test
    void theRowWithContentStandsAndTheWinnerBreaksTies() {
        assertEquals(AccountFrom.LOSER, ClientMergeRules.monthControlKeep(false, true));
        assertEquals(AccountFrom.WINNER, ClientMergeRules.monthControlKeep(true, false));
        assertEquals(AccountFrom.WINNER, ClientMergeRules.monthControlKeep(true, true));
        assertEquals(AccountFrom.WINNER, ClientMergeRules.monthControlKeep(false, false));
    }

    // ---- D2: two accounts — a person decides ---------------------------------------

    @Test
    void withOneAccountOrNoneThereIsNothingToDecide() {
        assertEquals(AccountFrom.WINNER, ClientMergeRules.requireAccountFrom(null, false));
        assertEquals(AccountFrom.WINNER, ClientMergeRules.requireAccountFrom("LOSER", false),
                "with one account the request's answer is ignored, not honoured");
    }

    @Test
    void withTwoAccountsTheRequestMustSayWhose() {
        assertEquals(AccountFrom.LOSER, ClientMergeRules.requireAccountFrom("loser", true));
        assertEquals(AccountFrom.WINNER, ClientMergeRules.requireAccountFrom(" WINNER ", true));

        for (String missing : new String[]{null, "", "both", "the higher band"}) {
            WebApplicationException refused = assertThrows(WebApplicationException.class,
                    () -> ClientMergeRules.requireAccountFrom(missing, true));
            assertEquals(400, refused.getResponse().getStatus());
            assertTrue(refused.getMessage().contains("accountFrom: WINNER or LOSER"), refused.getMessage());
        }
    }

    @Test
    void accountFromParsesLeniently() {
        assertEquals(AccountFrom.WINNER, AccountFrom.parse("winner"));
        assertNull(AccountFrom.parse(null));
        assertNull(AccountFrom.parse("neither"));
    }
}
