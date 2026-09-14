package dk.trustworks.intranet.aggregates.crm.account.services;

import dk.trustworks.intranet.aggregates.crm.account.dto.AccountRelationshipDTO;
import dk.trustworks.intranet.aggregates.crm.account.model.enums.AccountBand;
import dk.trustworks.intranet.aggregates.crm.account.model.enums.AccountRelationship;
import dk.trustworks.intranet.aggregates.crm.account.services.AccountService.RelationshipFacts;
import org.junit.jupiter.api.Test;

import java.time.LocalDate;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The four relationships (relationships spec §2.1).
 *
 * <p>Fast tier: {@link RelationshipFacts#describe} is pure, so the whole rule set is held
 * here without booting Quarkus or a database. That matters more than usual — this is the
 * logic that decides what a company IS on every surface in the CRM, and it is derived on
 * every read rather than stored, so there is no column anybody could inspect afterwards to
 * see that it went wrong.
 */
class AccountRelationshipDerivationTest {

    private static final LocalDate TODAY = LocalDate.of(2026, 9, 14);
    private static final String CLIENT = "c-1";

    /** Nothing at all: no assignment, no contract, no work row, no lead. */
    private static RelationshipFacts nothing() {
        return new RelationshipFacts(Map.of(), Map.of(), Map.of(), Set.of(), Map.of(), Map.of());
    }

    private static RelationshipFacts facts(LocalDate firstAssignment,
                                           LocalDate lastAssignment,
                                           LocalDate runningUntil,
                                           boolean everWorked,
                                           int contracts,
                                           int openLeads) {
        return new RelationshipFacts(
                firstAssignment == null ? Map.of() : Map.of(CLIENT, firstAssignment),
                lastAssignment == null ? Map.of() : Map.of(CLIENT, lastAssignment),
                runningUntil == null ? Map.of() : Map.of(CLIENT, runningUntil),
                everWorked ? Set.of(CLIENT) : Set.of(),
                contracts == 0 ? Map.of() : Map.of(CLIENT, contracts),
                openLeads == 0 ? Map.of() : Map.of(CLIENT, openLeads));
    }

    private static AccountRelationshipDTO describe(RelationshipFacts facts, AccountBand band) {
        return facts.describe(CLIENT, band, TODAY);
    }

    // ------------------------------------------------------------------------
    // The four rules, in the order they are decided
    // ------------------------------------------------------------------------

    @Test
    void anAssignmentEndingTodayOrLaterIsACustomer() {
        assertEquals(AccountRelationship.CUSTOMER.name(),
                describe(facts(LocalDate.of(2019, 1, 1), TODAY, TODAY, true, 3, 0), AccountBand.BACKLOG)
                        .relationship());
        assertEquals(AccountRelationship.CUSTOMER.name(),
                describe(facts(LocalDate.of(2019, 1, 1), TODAY.plusYears(1), TODAY.plusYears(1), true, 3, 0),
                        AccountBand.BACKLOG).relationship());
    }

    /**
     * The bug this predicate exists to fix.
     *
     * <p>The band used to be derived from CONTRACT STATUS, and contracts are almost never
     * closed: production held 622 SIGNED against 11 CLOSED. 129 clients read as running
     * and 103 of them had nobody on site — the last consultant had rolled off, some of
     * them years before. A contract that is still SIGNED with an assignment that ended
     * yesterday is a FORMER customer, and nothing about the contract's status changes that.
     */
    @Test
    void anAssignmentThatEndedYesterdayIsAFormerCustomerHoweverOpenTheContractIs() {
        AccountRelationshipDTO row =
                describe(facts(LocalDate.of(2019, 1, 1), TODAY.minusDays(1), TODAY.minusDays(1), true, 3, 0),
                        AccountBand.BACKLOG);
        assertEquals(AccountRelationship.FORMER.name(), row.relationship());
        assertEquals(TODAY.minusDays(1), row.lastWorked());
        assertEquals(LocalDate.of(2019, 1, 1), row.customerSince());
        assertEquals(3, row.contractCount());
    }

    @Test
    void workWithNoAssignmentIsStillAFormerCustomer() {
        // A work row and no contract_consultants row at all: somebody registered hours
        // here once. That is a company we have worked for, and calling it a contact would
        // be losing the only evidence there is.
        assertEquals(AccountRelationship.FORMER.name(),
                describe(facts(null, null, null, true, 0, 0), AccountBand.BACKLOG).relationship());
    }

    @Test
    void neverBilledWithAnOpenLeadIsAProspect() {
        AccountRelationshipDTO row = describe(facts(null, null, null, false, 0, 2), AccountBand.BACKLOG);
        assertEquals(AccountRelationship.PROSPECT.name(), row.relationship());
        assertEquals(2, row.openLeads());
        assertNull(row.customerSince());
        assertNull(row.lastWorked());
    }

    @Test
    void neverBilledWithADecidedBandAboveBacklogIsAProspect() {
        assertEquals(AccountRelationship.PROSPECT.name(),
                describe(nothing(), AccountBand.ACTIVE).relationship());
        assertEquals(AccountRelationship.PROSPECT.name(),
                describe(nothing(), AccountBand.STRATEGIC).relationship());
    }

    @Test
    void everythingElseIsAContact() {
        AccountRelationshipDTO row = describe(nothing(), AccountBand.BACKLOG);
        assertEquals(AccountRelationship.CONTACT.name(), row.relationship());
        assertEquals(0, row.contractCount());
        assertEquals(0, row.openLeads());
        assertFalse(row.winBack());
        assertFalse(row.expiringWithin90d());
    }

    // ------------------------------------------------------------------------
    // Win-back: the history is the fact, the band is the intent, and BOTH show
    // ------------------------------------------------------------------------

    @Test
    void aFormerCustomerBeingChasedStaysFormerAndIsFlaggedWinBack() {
        AccountRelationshipDTO withLead =
                describe(facts(LocalDate.of(2018, 1, 1), LocalDate.of(2024, 3, 31), LocalDate.of(2024, 3, 31), true, 4, 1),
                        AccountBand.BACKLOG);
        assertEquals(AccountRelationship.FORMER.name(), withLead.relationship());
        assertTrue(withLead.winBack());

        AccountRelationshipDTO withBand =
                describe(facts(LocalDate.of(2018, 1, 1), LocalDate.of(2024, 3, 31), LocalDate.of(2024, 3, 31), true, 4, 0),
                        AccountBand.ACTIVE);
        assertEquals(AccountRelationship.FORMER.name(), withBand.relationship());
        assertTrue(withBand.winBack());
    }

    @Test
    void onlyAFormerCustomerCanBeAWinBack() {
        // A prospect being pursued is not "won back" — there was nothing to lose.
        assertFalse(describe(facts(null, null, null, false, 0, 1), AccountBand.ACTIVE).winBack());
        // Nor is a current customer with an open extension lead.
        assertFalse(describe(facts(LocalDate.of(2019, 1, 1), TODAY.plusMonths(6), TODAY.plusMonths(6), true, 2, 1),
                AccountBand.ACTIVE).winBack());
    }

    // ------------------------------------------------------------------------
    // Expiring: "every running assignment ends within 90 days"
    // ------------------------------------------------------------------------

    @Test
    void aCustomerWhoseLastAssignmentEndsInsideNinetyDaysIsExpiring() {
        assertTrue(describe(facts(LocalDate.of(2019, 1, 1), TODAY.plusDays(89), TODAY.plusDays(89), true, 1, 0),
                AccountBand.ACTIVE).expiringWithin90d());
        assertTrue(describe(facts(LocalDate.of(2019, 1, 1), TODAY.plusDays(90), TODAY.plusDays(90), true, 1, 0),
                AccountBand.ACTIVE).expiringWithin90d());
    }

    @Test
    void oneAssignmentRunningPastTheWindowIsEnoughToNotBeExpiring() {
        // runningUntil is the MAX over the client's assignments, so a second contract
        // running into next year is what keeps this false — which is the whole question
        // the flag answers: is there anything left after the one that is ending.
        assertFalse(describe(facts(LocalDate.of(2019, 1, 1), TODAY.plusDays(400), TODAY.plusDays(400), true, 2, 0),
                AccountBand.ACTIVE).expiringWithin90d());
    }

    @Test
    void onlyACustomerCanBeExpiring() {
        assertFalse(describe(facts(LocalDate.of(2019, 1, 1), TODAY.minusDays(1), TODAY.minusDays(1), true, 1, 0),
                AccountBand.ACTIVE).expiringWithin90d());
        assertFalse(describe(nothing(), AccountBand.ACTIVE).expiringWithin90d());
    }

    // ------------------------------------------------------------------------
    // The band never changes the relationship of a company we have worked for
    // ------------------------------------------------------------------------

    @Test
    void aBandDecisionCannotTurnACustomerIntoAProspect() {
        for (AccountBand band : AccountBand.values()) {
            assertEquals(AccountRelationship.CUSTOMER.name(),
                    describe(facts(LocalDate.of(2019, 1, 1), TODAY.plusDays(30), TODAY.plusDays(30), true, 1, 5), band)
                            .relationship(),
                    "band " + band + " must not change what the company IS");
        }
    }
}
