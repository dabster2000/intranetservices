package dk.trustworks.intranet.aggregates.crm.signal.services;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Who may decide a signal (CRM spec §3.4): the account's owner; the sector lead when the
 * account has no owner; management anywhere. The database halves — is this person the
 * owner, is this person the current sector lead — are exercised during verification; the
 * rule that combines them is locked here.
 */
class AccountSignalDecisionRuleTest {

    @Test
    void theOwnerDecidesTheirOwnAccountsSignals() {
        assertTrue(AccountSignalService.mayDecide(false, true, true, false));
    }

    @Test
    void aColleagueWhoIsNeitherOwnerNorLeadMayNot() {
        assertFalse(AccountSignalService.mayDecide(false, false, true, false));
        assertFalse(AccountSignalService.mayDecide(false, false, false, false));
    }

    /** Spec §3.4: "or the sector lead when there is no owner" — and only then. */
    @Test
    void theSectorLeadDecidesOnlyWhenTheAccountHasNoOwner() {
        assertTrue(AccountSignalService.mayDecide(false, false, false, true));
        assertFalse(AccountSignalService.mayDecide(false, false, true, true),
                "an owned account's signals belong to its owner, not to the sector lead");
    }

    @Test
    void managementDecidesAnywhere() {
        assertTrue(AccountSignalService.mayDecide(true, false, true, false));
        assertTrue(AccountSignalService.mayDecide(true, false, false, false));
    }
}
