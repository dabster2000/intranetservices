package dk.trustworks.intranet.aggregates.crm.signal.dto;

import org.junit.jupiter.api.Test;

import java.util.Arrays;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * How a capture's accounts and colleagues are read off the request body (V593).
 *
 * <p>This is where the production defect actually lands. A line naming two accounts
 * reaches the backend as two uuids, and if either the union or the de-duplication here is
 * wrong, the signal goes back to being filed on one account — which is exactly what
 * happened on 2026-09-13, silently.
 *
 * <p>Plain JUnit: the record is pure, so the fast tier that gates deploys can hold it.
 */
class AccountSignalRequestTest {

    private static final String RIGSPOLITIET = "7d34db42-9d11-4fe2-84df-449a46cdfcc9";
    private static final String KOMBIT = "7d5e8dc7-4c5f-4467-adf2-d72ec884a9a2";
    private static final String TOBIAS = "ca0e1027-061f-49e7-b66a-a487c815f5a0";

    private static AccountSignalRequest request(String single, List<String> plural, List<String> colleagues) {
        return new AccountSignalRequest(single, plural, "heard something", null, null, null, null, colleagues, null);
    }

    /**
     * Companies the author named that Intra does not know yet (relationships spec §4.3).
     *
     * <p>Absent is the common case and must read as an empty list, not as a null the
     * service has to guard — the same posture as the colleague list above.
     */
    @Test
    void namedCompaniesAreDroppedWhenBlankAndNeverNull() {
        assertEquals(List.of(), request(null, List.of(RIGSPOLITIET), null).allNewCompanies());

        AccountSignalRequest withCompanies = new AccountSignalRequest(
                null, List.of(), "heard something", null, null, null, null, null,
                java.util.Arrays.asList(
                        new AccountSignalRequest.NewCompany("DSB", "PUBLIC"),
                        null,
                        new AccountSignalRequest.NewCompany("  ", "PUBLIC"),
                        new AccountSignalRequest.NewCompany(null, null)));

        assertEquals(1, withCompanies.allNewCompanies().size());
        assertEquals("DSB", withCompanies.allNewCompanies().get(0).name());
    }

    /** The case this release exists for: both accounts must come through, in order. */
    @Test
    void bothAccountsSurviveInTheOrderTheyWereNamed() {
        AccountSignalRequest body = request(null, List.of(RIGSPOLITIET, KOMBIT), null);
        assertEquals(List.of(RIGSPOLITIET, KOMBIT), body.allClientUuids(),
                "the account the author led with must stay first");
    }

    /**
     * A pre-V593 caller sends the singular field. It must still work — the Slack entry
     * points are specified against that shape and are not built yet.
     */
    @Test
    void theLegacySingularFieldStillWorksOnItsOwn() {
        assertEquals(List.of(KOMBIT), request(KOMBIT, null, null).allClientUuids());
    }

    /** Both fields at once is one client, not two. The singular leads. */
    @Test
    void singularAndPluralAreUnionedNotAppended() {
        AccountSignalRequest body = request(KOMBIT, List.of(KOMBIT, RIGSPOLITIET), null);
        assertEquals(List.of(KOMBIT, RIGSPOLITIET), body.allClientUuids());
    }

    /**
     * The same account twice is one row, not two. Without this a double-click on the same
     * {@code @} suggestion files the signal on one account twice and gives its owner two
     * identical things to decide.
     */
    @Test
    void repeatedAccountsAreDeDuplicated() {
        AccountSignalRequest body = request(null, List.of(KOMBIT, KOMBIT, RIGSPOLITIET, KOMBIT), null);
        assertEquals(List.of(KOMBIT, RIGSPOLITIET), body.allClientUuids());
    }

    @Test
    void blanksNullsAndWhitespaceAreDropped() {
        AccountSignalRequest body = request("  ", Arrays.asList(null, "", "   ", "  " + KOMBIT + "  "), null);
        assertEquals(List.of(KOMBIT), body.allClientUuids(), "a padded uuid is the same uuid");
    }

    /** No accounts at all is an empty list, never null — the service refuses it by size. */
    @Test
    void nothingNamedIsAnEmptyListNotNull() {
        AccountSignalRequest body = request(null, null, null);
        assertTrue(body.allClientUuids().isEmpty());
        assertTrue(body.allColleagueUuids().isEmpty());
    }

    /** The other half of the defect: the colleague the line named. */
    @Test
    void colleaguesAreTrimmedAndDeDuplicated() {
        AccountSignalRequest body = request(KOMBIT, null, Arrays.asList(TOBIAS, "  " + TOBIAS + " ", null, ""));
        assertEquals(List.of(TOBIAS), body.allColleagueUuids());
    }
}
