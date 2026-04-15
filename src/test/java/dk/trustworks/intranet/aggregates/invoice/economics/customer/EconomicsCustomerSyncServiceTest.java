package dk.trustworks.intranet.aggregates.invoice.economics.customer;

import dk.trustworks.intranet.dao.crm.model.Client;
import dk.trustworks.intranet.dao.crm.model.enums.ClientType;
import jakarta.ws.rs.WebApplicationException;
import jakarta.ws.rs.core.Response;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doNothing;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Unit tests for {@link EconomicsCustomerSyncService}. Plain JUnit + Mockito —
 * no Quarkus boot required because every collaborator is a mockable seam.
 *
 * SPEC-INV-001 §3.3, §6.3, §6.8.
 */
class EconomicsCustomerSyncServiceTest {

    // Matches Trustworks A/S in the AgreementDefaultsRegistry so the service
    // finds defaults for this companyuuid.
    private static final String COMPANY = "d8894494-2fb4-4f72-9e05-e6032e6dd691";

    private ClientEconomicsCustomerRepository repo;
    private ClientEconomicsSyncFailureRepository failures;
    private AgreementResolver agreementResolver;
    private AgreementDefaultsRegistry agreementDefaults;
    private EconomicsCustomerApiClient api;
    private ClientToEconomicsCustomerMapper mapper;

    private EconomicsCustomerSyncService service;

    @BeforeEach
    void setUp() {
        repo = mock(ClientEconomicsCustomerRepository.class);
        failures = mock(ClientEconomicsSyncFailureRepository.class);
        agreementResolver = mock(AgreementResolver.class);
        agreementDefaults = new AgreementDefaultsRegistry();
        api = mock(EconomicsCustomerApiClient.class);
        mapper = new ClientToEconomicsCustomerMapper();

        when(agreementResolver.apiFor(COMPANY)).thenReturn(api);

        service = new EconomicsCustomerSyncService(repo, failures, agreementResolver, agreementDefaults, mapper);
    }

    // ----------------------- PUT (update) -----------------------

    @Test
    void updates_existing_customer_with_get_then_put_using_fresh_objectVersion() {
        Client c = makeClient("c-uuid", "Acme", "12345678", ClientType.CLIENT);
        ClientEconomicsCustomer existing = makeMapping("c-uuid", COMPANY, 101, "obj-v-old");
        when(repo.findByClientAndCompany("c-uuid", COMPANY)).thenReturn(Optional.of(existing));

        // Two GETs: one to build the PUT body, a second after the PUT to pick up
        // the new objectVersion (e-conomic's PUT response has an empty body).
        when(api.getCustomer(101))
                .thenReturn(makeRemote(101, "obj-v-fresh"))
                .thenReturn(makeRemote(101, "obj-v-new"));
        // updateCustomer is void — Mockito's default is "do nothing", no stub needed.

        service.syncToCompany(c, COMPANY);

        verify(api, times(2)).getCustomer(101);
        ArgumentCaptor<EconomicsCustomerDto> body = ArgumentCaptor.forClass(EconomicsCustomerDto.class);
        verify(api).updateCustomer(eq(101), body.capture());
        assertEquals("obj-v-fresh", body.getValue().getObjectVersion());
        assertEquals(101, body.getValue().getCustomerNumber());

        // Persisted mapping should carry the post-PUT objectVersion from the re-GET.
        ArgumentCaptor<ClientEconomicsCustomer> row = ArgumentCaptor.forClass(ClientEconomicsCustomer.class);
        verify(repo).persist(row.capture());
        assertEquals("obj-v-new", row.getValue().getObjectVersion());
        // Pairing source preserved.
        assertEquals(PairingSource.MANUAL, row.getValue().getPairingSource());
    }

    // ----------------------- POST (create) -----------------------

    @Test
    void creates_customer_when_no_mapping_exists_and_stores_pairing_as_CREATED() {
        Client c = makeClient("c-uuid", "New Co", "99999999", ClientType.CLIENT);
        when(repo.findByClientAndCompany("c-uuid", COMPANY)).thenReturn(Optional.empty());

        when(api.createCustomer(any())).thenReturn(makeRemote(202, "obj-v-1"));

        service.syncToCompany(c, COMPANY);

        // POST body carries the derived customerNumber (CVR as int).
        ArgumentCaptor<EconomicsCustomerDto> body = ArgumentCaptor.forClass(EconomicsCustomerDto.class);
        verify(api).createCustomer(body.capture());
        assertEquals(99999999, body.getValue().getCustomerNumber());

        // Mapping row persisted with PairingSource.CREATED and the server's number.
        ArgumentCaptor<ClientEconomicsCustomer> row = ArgumentCaptor.forClass(ClientEconomicsCustomer.class);
        verify(repo).persist(row.capture());
        assertEquals(202, row.getValue().getCustomerNumber());
        assertEquals(PairingSource.CREATED, row.getValue().getPairingSource());
        assertEquals("obj-v-1", row.getValue().getObjectVersion());
    }

    // ----------------------- 409 conflict retry -----------------------

    @Test
    void on_409_conflict_retries_put_with_fresh_objectVersion() {
        Client c = makeClient("c-uuid", "Acme", "12345678", ClientType.CLIENT);
        ClientEconomicsCustomer existing = makeMapping("c-uuid", COMPANY, 101, "obj-v-old");
        when(repo.findByClientAndCompany("c-uuid", COMPANY)).thenReturn(Optional.of(existing));

        // GET ordering after void updateCustomer:
        //   1st: build body for first PUT (fails 409)
        //   2nd: build body for retry PUT (succeeds)
        //   3rd: re-GET after successful PUT to pick up new objectVersion
        when(api.getCustomer(101))
                .thenReturn(makeRemote(101, "obj-v-1"))
                .thenReturn(makeRemote(101, "obj-v-2"))
                .thenReturn(makeRemote(101, "obj-v-2-after-put"));
        doThrow(new WebApplicationException(Response.status(409).build()))
                .doNothing()
                .when(api).updateCustomer(eq(101), any());

        service.syncToCompany(c, COMPANY);

        verify(api, times(3)).getCustomer(101);
        verify(api, times(2)).updateCustomer(eq(101), any());
    }

    // ----------------------- failure recording -----------------------

    @Test
    void non_conflict_error_records_failure_and_throws_SyncFailedException() {
        Client c = makeClient("c-uuid", "Acme", "12345678", ClientType.CLIENT);
        when(repo.findByClientAndCompany("c-uuid", COMPANY)).thenReturn(Optional.empty());
        when(api.createCustomer(any()))
                .thenThrow(new WebApplicationException(Response.status(500).build()));
        when(failures.findByClientAndCompany("c-uuid", COMPANY)).thenReturn(Optional.empty());

        assertThrows(SyncFailedException.class, () -> service.syncToCompany(c, COMPANY));

        ArgumentCaptor<ClientEconomicsSyncFailure> cap = ArgumentCaptor.forClass(ClientEconomicsSyncFailure.class);
        verify(failures).persist(cap.capture());
        ClientEconomicsSyncFailure f = cap.getValue();
        assertEquals("c-uuid", f.getClientUuid());
        assertEquals(COMPANY, f.getCompanyUuid());
        assertEquals(1, f.getAttemptCount());
        assertTrue(f.getLastError().contains("500"));
    }

    @Test
    void success_after_previous_failure_clears_failure_row() {
        Client c = makeClient("c-uuid", "Acme", "12345678", ClientType.CLIENT);
        when(repo.findByClientAndCompany("c-uuid", COMPANY)).thenReturn(Optional.empty());
        when(api.createCustomer(any())).thenReturn(makeRemote(101, "obj-v-1"));

        ClientEconomicsSyncFailure prior = new ClientEconomicsSyncFailure();
        prior.setUuid("f");
        prior.setClientUuid("c-uuid");
        prior.setCompanyUuid(COMPANY);
        prior.setAttemptCount(3);
        when(failures.findByClientAndCompany("c-uuid", COMPANY)).thenReturn(Optional.of(prior));

        service.syncToCompany(c, COMPANY);

        verify(failures).delete(prior);
    }

    @Test
    void attempt_count_increments_across_consecutive_failures() {
        Client c = makeClient("c-uuid", "Acme", "12345678", ClientType.CLIENT);
        when(repo.findByClientAndCompany("c-uuid", COMPANY)).thenReturn(Optional.empty());
        when(api.createCustomer(any()))
                .thenThrow(new WebApplicationException(Response.status(500).build()));

        ClientEconomicsSyncFailure prior = new ClientEconomicsSyncFailure();
        prior.setUuid("f");
        prior.setClientUuid("c-uuid");
        prior.setCompanyUuid(COMPANY);
        prior.setAttemptCount(5);
        when(failures.findByClientAndCompany("c-uuid", COMPANY)).thenReturn(Optional.of(prior));

        assertThrows(SyncFailedException.class, () -> service.syncToCompany(c, COMPANY));

        ArgumentCaptor<ClientEconomicsSyncFailure> cap = ArgumentCaptor.forClass(ClientEconomicsSyncFailure.class);
        verify(failures).persist(cap.capture());
        // Attempt 6 crosses the threshold → status flips to ABANDONED.
        assertEquals(6, cap.getValue().getAttemptCount());
        assertEquals("ABANDONED", cap.getValue().getStatus());
    }

    // ----------------------- syncToAllCompanies ---------------------

    @Test
    void syncToAllCompanies_continues_after_one_agreement_fails() {
        Client c = makeClient("c-uuid", "Acme", "12345678", ClientType.CLIENT);
        when(repo.findByClientAndCompany("c-uuid", COMPANY)).thenReturn(Optional.empty());
        // Throws a non-409 error so it's recorded + swallowed by the outer loop.
        when(api.createCustomer(any()))
                .thenThrow(new WebApplicationException(Response.status(500).build()));
        when(failures.findByClientAndCompany("c-uuid", COMPANY)).thenReturn(Optional.empty());

        // Must not throw — syncToAllCompanies collects failures per-agreement.
        service.syncToAllCompanies(c);

        verify(failures).persist(any(ClientEconomicsSyncFailure.class));
    }

    @Test
    void syncToCompany_with_unconfigured_company_throws_409_and_does_not_call_api() {
        Client c = makeClient("c-uuid", "Acme", "12345678", ClientType.CLIENT);
        assertThrows(WebApplicationException.class,
                () -> service.syncToCompany(c, "00000000-0000-0000-0000-000000000000"));

        verify(api, never()).createCustomer(any());
        verify(api, never()).updateCustomer(eq(0), any());
    }

    // ----------------------- derivation helpers ---------------------

    @Test
    void derives_customerNumber_from_cvr_when_numeric() {
        Client c = makeClient("uuid", "X", "86001519", ClientType.CLIENT);
        assertEquals(86001519, EconomicsCustomerSyncService.deriveCustomerNumber(c));
    }

    @Test
    void derives_customerNumber_from_uuid_hash_when_cvr_missing() {
        Client c = makeClient("f47ac10b-58cc-4372-a567-0e02b2c3d479", "X", null, ClientType.CLIENT);
        int n = EconomicsCustomerSyncService.deriveCustomerNumber(c);
        assertTrue(n >= 1 && n <= 999_999_999, "customerNumber out of range: " + n);
    }

    // ----------------------- helpers -----------------------

    private static Client makeClient(String uuid, String name, String cvr, ClientType type) {
        Client c = new Client();
        c.setUuid(uuid);
        c.setName(name);
        c.setCvr(cvr);
        c.setType(type);
        c.setCurrency("DKK");
        return c;
    }

    private static ClientEconomicsCustomer makeMapping(String clientUuid, String companyUuid,
                                                       int customerNumber, String objectVersion) {
        ClientEconomicsCustomer m = new ClientEconomicsCustomer();
        m.setUuid("m-" + clientUuid);
        m.setClientUuid(clientUuid);
        m.setCompanyUuid(companyUuid);
        m.setCustomerNumber(customerNumber);
        m.setObjectVersion(objectVersion);
        m.setPairingSource(PairingSource.MANUAL);
        return m;
    }

    private static EconomicsCustomerDto makeRemote(int customerNumber, String objectVersion) {
        EconomicsCustomerDto d = new EconomicsCustomerDto();
        d.setCustomerNumber(customerNumber);
        d.setObjectVersion(objectVersion);
        d.setName("remote-name");
        return d;
    }
}
