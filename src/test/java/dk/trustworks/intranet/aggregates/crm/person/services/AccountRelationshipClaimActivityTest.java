package dk.trustworks.intranet.aggregates.crm.person.services;

import dk.trustworks.intranet.aggregates.crm.person.model.AccountPerson;
import dk.trustworks.intranet.aggregates.crm.person.model.AccountRelationClaim;
import dk.trustworks.intranet.dao.crm.model.ClientActivityLog;
import dk.trustworks.intranet.dao.crm.services.ClientActivityLogService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.MockedConstruction;
import org.mockito.MockedStatic;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockConstruction;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * What a claim is allowed to write into {@code client_activity_log} — and the one action it
 * must never write.
 *
 * <p><b>The defect this pins.</b> A first claim used to be logged as {@code CLIENT} +
 * {@code CREATED}. The claim service's own javadoc reasoned about
 * {@code AccountActivityService.relationshipRows}, which filters {@code field_name = 'type'}
 * and is genuinely unaffected — and missed the OTHER reader.
 * {@code AccountService.addedByForAll} selects {@code min(modified_by)} over
 * {@code entity_type = 'CLIENT' and action = 'CREATED'} with no field-name predicate, and
 * that is the {@code addedBy} field on every row of {@code GET /accounts} — "by &lt;first
 * name&gt;" in the Contacts view. {@code modified_by} is not the actor this service
 * validated either: {@code ClientActivityLogService.resolveCurrentUser()} reads
 * {@code RequestHeaderHolder} itself, so it is whoever filed the claim. Every employee holds
 * {@code signals:write} and {@code min()} over a {@code CHAR(36)} is lexicographic, so
 * roughly half of all first claims silently rewrote who added the company — and on a client
 * older than the activity log the claim was the only {@code CREATED} row at all.
 *
 * <p>Nothing about that is visible to a compiler, to a type, or to the timeline the service
 * was reasoning about: the wrong name simply appears in a column on a different page. So it
 * is pinned here, in the DB-free tier that gates every deploy.
 *
 * <p><b>Why {@code mockConstruction}.</b> The first-claim branch does
 * {@code new AccountRelationClaim()} and then {@code persist()}, and a Panache
 * {@code persist()} outside a container reaches for an Arc-managed session that does not
 * exist. Mocking the construction gives the service an inert entity so the branch runs to
 * the log write, which is the only thing under test. The static mocks stand in for the two
 * finders — the service never touches the database for anything else.
 */
class AccountRelationshipClaimActivityTest {

    private static final String CLIENT = "b6c7b4f9-b3fb-4d61-9f1e-9de4a1ae8a30";
    private static final String PERSON = "4e2f1f30-6a4b-49cd-9d4f-7c0a1f2b3c4d";

    /** A colleague with {@code signals:write} — i.e. anybody at all. */
    private static final String CLAIMANT = "7948c5e8-162c-4053-b905-0f59a21d7746";

    private AccountRelationshipClaimService service;
    private ClientActivityLogService activityLog;

    @BeforeEach
    void setUp() {
        service = new AccountRelationshipClaimService();
        activityLog = mock(ClientActivityLogService.class);
        service.activityLogService = activityLog;
    }

    /**
     * A FIRST claim — the dangerous one. It is a new row in
     * {@code account_relation_claim} and it still logs {@code MODIFIED}, because the log is
     * shared with a column that reads {@code CREATED} as "this is who put the company into
     * Intra". The {@code previous} value (null here) already carries new-versus-update.
     */
    @Test
    void aFirstClaimIsLoggedAsModifiedAndNeverAsCreated() {
        try (MockedStatic<AccountPerson> people = mockStatic(AccountPerson.class);
             MockedStatic<AccountRelationClaim> claims = mockStatic(AccountRelationClaim.class);
             MockedConstruction<AccountRelationClaim> built = mockConstruction(AccountRelationClaim.class)) {

            people.when(() -> AccountPerson.findOnClient(CLIENT, PERSON)).thenReturn(person());
            claims.when(() -> AccountRelationClaim.findByPersonAndUser(PERSON, CLAIMANT)).thenReturn(null);

            service.upsert(CLIENT, PERSON, CLAIMANT, 3, "arbejdede sammen i KMD 2019-21");

            assertEquals(1, built.constructed().size(), "a first claim does insert a row");
        }

        verify(activityLog).logChange(eq(CLIENT), eq(ClientActivityLog.TYPE_CLIENT), eq(PERSON),
                isNull(), eq(ClientActivityLog.ACTION_MODIFIED),
                eq(AccountRelationshipClaimService.ACTIVITY_FIELD), isNull(), eq("3"));
        verifyNothingWasLoggedAsCreated();
    }

    /** The second call from the same colleague. Same action, and the old strength as {@code previous}. */
    @Test
    void anUpdatedClaimCarriesThePreviousStrength() {
        AccountRelationClaim existing = mock(AccountRelationClaim.class);
        when(existing.getStrength()).thenReturn(2);

        try (MockedStatic<AccountPerson> people = mockStatic(AccountPerson.class);
             MockedStatic<AccountRelationClaim> claims = mockStatic(AccountRelationClaim.class)) {

            people.when(() -> AccountPerson.findOnClient(CLIENT, PERSON)).thenReturn(person());
            claims.when(() -> AccountRelationClaim.findByPersonAndUser(PERSON, CLAIMANT)).thenReturn(existing);

            service.upsert(CLIENT, PERSON, CLAIMANT, 4, null);
        }

        verify(activityLog).logChange(eq(CLIENT), eq(ClientActivityLog.TYPE_CLIENT), eq(PERSON),
                isNull(), eq(ClientActivityLog.ACTION_MODIFIED),
                eq(AccountRelationshipClaimService.ACTIVITY_FIELD), eq("2"), eq("4"));
        verifyNothingWasLoggedAsCreated();
    }

    /**
     * The second half of the fix lives in {@code AccountService.addedByForAll}, which now
     * also requires {@code field_name is null}. That backstop only works because every row
     * this service writes names a field — so the constant may never become null or blank,
     * and may never become {@code 'type'} (which is the account timeline's own filter).
     */
    @Test
    void everyClaimRowNamesAFieldSoTheAddedByBackstopHolds() {
        assertNotNull(AccountRelationshipClaimService.ACTIVITY_FIELD,
                "a null field name would make a claim row indistinguishable from a client creation");
        assertFalse(AccountRelationshipClaimService.ACTIVITY_FIELD.isBlank(),
                "and so would a blank one — the backstop tests IS NULL, but an empty name is no name");
        assertNotEquals("type", AccountRelationshipClaimService.ACTIVITY_FIELD,
                "'type' is AccountActivityService.relationshipRows' filter — it renders 'Became a customer'");
    }

    private void verifyNothingWasLoggedAsCreated() {
        verify(activityLog, never()).logChange(any(), any(), any(), any(),
                eq(ClientActivityLog.ACTION_CREATED), any(), any(), any());
    }

    /** The registry row the claim is about. Real setters, no session behind them. */
    private static AccountPerson person() {
        AccountPerson person = new AccountPerson();
        person.setUuid(PERSON);
        person.setClientUuid(CLIENT);
        person.setName("Dorte Kirkegaard");
        return person;
    }
}
