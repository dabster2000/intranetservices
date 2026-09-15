package dk.trustworks.intranet.aggregates.crm.account.resources;

import dk.trustworks.intranet.aggregates.crm.account.dto.AccountDomainsRequest;
import dk.trustworks.intranet.aggregates.crm.account.dto.ClientDomainDTO;
import dk.trustworks.intranet.aggregates.crm.account.services.AccountService;
import dk.trustworks.intranet.aggregates.crm.calendar.services.CalendarRecoveryService;
import dk.trustworks.intranet.aggregates.crm.person.services.AccountPersonService;
import dk.trustworks.intranet.security.RequestHeaderHolder;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.InOrder;

import java.lang.reflect.Field;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Where the person-registry rebuild hangs off a domain edit — and why it cannot hang off the
 * service method.
 *
 * <p><b>The defect this pins.</b> {@code AccountService.replaceDomains} is
 * {@code @Transactional} and used to call the rebuild before its own commit.
 * {@code AccountPersonService.rebuild} opens {@code QuarkusTransaction.requiringNew()}, and
 * requiring-new SUSPENDS the caller's transaction rather than joining it — so the rebuild ran
 * on a connection that could not see the {@code client_domain} rows the same request had just
 * written, and recomputed every placement from the OLD domain set. Nothing failed: the write
 * committed, the rebuild reported a cheerful count, and the relationships tab kept its
 * pre-edit classification until the 03:00 sweep or a manual Refresh. To the person who just
 * corrected a domain that reads as the edit not having worked.
 *
 * <p>The two sibling hooks already had it right and say so in their own javadoc:
 * {@code AccountSignalResource} and {@code TrustLinkResource} both call the rebuild from the
 * RESOURCE, after the transactional service method returns. This is the third.
 *
 * <p>Plain JUnit with mocked collaborators — the ordering and the never-fatal rule are the
 * contract, and a {@code @QuarkusTest} would prove neither without a database and a clock.
 */
class AccountDomainRebuildHookTest {

    private static final String CLIENT = "b6c7b4f9-b3fb-4d61-9f1e-9de4a1ae8a30";
    private static final String ACTOR = "7948c5e8-162c-4053-b905-0f59a21d7746";

    private static final AccountDomainsRequest REQUEST = new AccountDomainsRequest(List.of("kmd.dk"));
    private static final List<ClientDomainDTO> SAVED =
            List.of(new ClientDomainDTO("5a1e6f28-1a0b-4a1e-9a9f-0d9a6f7b1c22", "kmd.dk", "MANUAL"));

    private AccountResource resource;
    private AccountService accountService;
    private AccountPersonService personService;

    @BeforeEach
    void setUp() {
        resource = new AccountResource();
        accountService = mock(AccountService.class);
        personService = mock(AccountPersonService.class);

        RequestHeaderHolder headers = new RequestHeaderHolder();
        headers.setUserUuid(ACTOR);

        resource.accountService = accountService;
        resource.personService = personService;
        resource.requestHeaderHolder = headers;
        // A domain edit also changes which meetings the calendar rules keep, so the endpoint asks
        // for a full replay. It swallows its own failures inside CalendarRecoveryService, which is
        // why a plain mock is enough here and why a throwing one would pin the wrong contract.
        resource.calendarRecovery = mock(CalendarRecoveryService.class);

        when(accountService.replaceDomains(CLIENT, REQUEST, ACTOR)).thenReturn(SAVED);
        when(personService.rebuild(CLIENT))
                .thenReturn(AccountPersonService.RebuildSummary.ran(1, 7, 9, 0, null));
    }

    /**
     * The order is the whole fix. The rebuild must start only once the service method — and
     * with it the transaction that wrote {@code client_domain} — has returned, because the
     * rebuild reads on a transaction of its own.
     */
    @Test
    void theRebuildRunsAfterTheDomainWriteAndNotInsideIt() {
        List<ClientDomainDTO> answer = resource.replaceDomains(CLIENT, REQUEST);

        InOrder order = inOrder(accountService, personService);
        order.verify(accountService).replaceDomains(CLIENT, REQUEST, ACTOR);
        order.verify(personService).rebuild(CLIENT);
        assertEquals(SAVED, answer, "and the endpoint still answers with the saved domains");
    }

    /**
     * A hook must never be the thing that loses somebody's edit. The domains are already
     * committed by the time the rebuild runs, so an exception escaping here would report a
     * failure for a write that succeeded — and the caller would edit again.
     */
    @Test
    void aFailedRebuildNeverFailsTheDomainEdit() {
        doThrow(new IllegalStateException("no connection")).when(personService).rebuild(CLIENT);

        List<ClientDomainDTO> answer = assertDoesNotThrow(() -> resource.replaceDomains(CLIENT, REQUEST));

        assertEquals(SAVED, answer);
        verify(accountService).replaceDomains(CLIENT, REQUEST, ACTOR);
    }

    /**
     * The structural half: {@code AccountService} must not hold the registry service at all.
     * Every write in that class runs inside {@code @Transactional}, so any hook reachable
     * from it would be a hook that reads the pre-write state — the defect above, reintroduced
     * by whoever adds the next one. Not holding the dependency is what makes that impossible
     * rather than merely discouraged.
     */
    @Test
    void theTransactionalServiceDoesNotHoldTheRegistryAtAll() {
        for (Field field : AccountService.class.getDeclaredFields()) {
            assertNotEquals(AccountPersonService.class, field.getType(),
                    "AccountService." + field.getName() + " puts the registry rebuild back inside a "
                            + "transaction that suspends for it — hook it from AccountResource instead");
        }
    }
}
