package dk.trustworks.intranet.aggregates.invoice.economics.customer;

import dk.trustworks.intranet.contracts.model.Contract;
import dk.trustworks.intranet.dao.crm.model.Client;
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
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Unit tests for {@link EconomicsCustomerContactSyncService}. Plain JUnit +
 * Mockito — no Quarkus boot required.
 *
 * SPEC-INV-001 §3.3.2, §6.8, §7.1 Phase G2.
 */
class EconomicsCustomerContactSyncServiceTest {

    private static final String COMPANY = "d8894494-2fb4-4f72-9e05-e6032e6dd691";

    private ClientEconomicsCustomerRepository customerRepo;
    private ClientEconomicsContactRepository contactRepo;
    private ClientEconomicsSyncFailureRepository failures;
    private ContactAgreementResolver agreementResolver;
    private AgreementDefaultsRegistry agreementDefaults;
    private EconomicsContactApiClient api;
    private ContractToEconomicsContactMapper mapper;

    private EconomicsCustomerContactSyncService service;

    @BeforeEach
    void setUp() {
        customerRepo = mock(ClientEconomicsCustomerRepository.class);
        contactRepo = mock(ClientEconomicsContactRepository.class);
        failures = mock(ClientEconomicsSyncFailureRepository.class);
        agreementResolver = mock(ContactAgreementResolver.class);
        agreementDefaults = new AgreementDefaultsRegistry();
        api = mock(EconomicsContactApiClient.class);
        mapper = new ContractToEconomicsContactMapper();

        when(agreementResolver.apiFor(COMPANY)).thenReturn(api);

        service = new EconomicsCustomerContactSyncService(
                customerRepo, contactRepo, failures, agreementResolver, agreementDefaults, mapper);
    }

    // ----------------------- POST (create) -----------------------

    @Test
    void creates_contact_when_missing_and_persists_mapping() {
        Contract ct = makeContract("Thomas", "thomas@x.dk");
        Client billing = makeClient("c-uuid");
        when(customerRepo.findByClientAndCompany("c-uuid", COMPANY))
                .thenReturn(Optional.of(makeCustomerMapping("c-uuid", COMPANY, 101)));
        when(contactRepo.findByClientCompanyAndName("c-uuid", COMPANY, "Thomas"))
                .thenReturn(Optional.empty());

        EconomicsContactDto created = new EconomicsContactDto();
        created.setCustomerContactNumber(999);
        created.setObjectVersion("v-1");
        when(api.createContact(any())).thenReturn(created);

        service.syncContactToCompany(ct, billing, COMPANY);

        ArgumentCaptor<EconomicsContactDto> body = ArgumentCaptor.forClass(EconomicsContactDto.class);
        verify(api).createContact(body.capture());
        assertEquals(101, body.getValue().getCustomerNumber());
        assertEquals("Thomas", body.getValue().getName());

        ArgumentCaptor<ClientEconomicsContact> persisted = ArgumentCaptor.forClass(ClientEconomicsContact.class);
        verify(contactRepo).persist(persisted.capture());
        assertEquals("c-uuid", persisted.getValue().getClientUuid());
        assertEquals(COMPANY, persisted.getValue().getCompanyUuid());
        assertEquals("Thomas", persisted.getValue().getContactName());
        assertEquals(999, persisted.getValue().getCustomerContactNumber());
        assertEquals("v-1", persisted.getValue().getObjectVersion());
        assertTrue(persisted.getValue().isReceiveEInvoices());
    }

    // ----------------------- PUT (update) -----------------------

    @Test
    void updates_existing_contact_with_get_then_put_using_fresh_objectVersion() {
        Contract ct = makeContract("Thomas", "thomas@x.dk");
        Client billing = makeClient("c-uuid");
        when(customerRepo.findByClientAndCompany("c-uuid", COMPANY))
                .thenReturn(Optional.of(makeCustomerMapping("c-uuid", COMPANY, 101)));

        ClientEconomicsContact existing = new ClientEconomicsContact();
        existing.setUuid("m");
        existing.setClientUuid("c-uuid");
        existing.setCompanyUuid(COMPANY);
        existing.setContactName("Thomas");
        existing.setCustomerContactNumber(777);
        existing.setObjectVersion("stale");
        when(contactRepo.findByClientCompanyAndName("c-uuid", COMPANY, "Thomas"))
                .thenReturn(Optional.of(existing));

        EconomicsContactDto fresh = new EconomicsContactDto();
        fresh.setCustomerContactNumber(777);
        fresh.setObjectVersion("v-fresh");
        when(api.getContact(777)).thenReturn(fresh);

        EconomicsContactDto updated = new EconomicsContactDto();
        updated.setCustomerContactNumber(777);
        updated.setObjectVersion("v-new");
        when(api.updateContact(eq(777), any())).thenReturn(updated);

        service.syncContactToCompany(ct, billing, COMPANY);

        verify(api).getContact(777);
        ArgumentCaptor<EconomicsContactDto> body = ArgumentCaptor.forClass(EconomicsContactDto.class);
        verify(api).updateContact(eq(777), body.capture());
        assertEquals("v-fresh", body.getValue().getObjectVersion());
        assertEquals(777, body.getValue().getCustomerContactNumber());

        ArgumentCaptor<ClientEconomicsContact> persisted = ArgumentCaptor.forClass(ClientEconomicsContact.class);
        verify(contactRepo).persist(persisted.capture());
        assertEquals("v-new", persisted.getValue().getObjectVersion());
    }

    // ----------------------- 409 conflict retry -----------------------

    @Test
    void on_409_conflict_retries_put_with_fresh_objectVersion() {
        Contract ct = makeContract("Thomas", "thomas@x.dk");
        Client billing = makeClient("c-uuid");
        when(customerRepo.findByClientAndCompany("c-uuid", COMPANY))
                .thenReturn(Optional.of(makeCustomerMapping("c-uuid", COMPANY, 101)));

        ClientEconomicsContact existing = new ClientEconomicsContact();
        existing.setUuid("m");
        existing.setClientUuid("c-uuid");
        existing.setCompanyUuid(COMPANY);
        existing.setContactName("Thomas");
        existing.setCustomerContactNumber(777);
        when(contactRepo.findByClientCompanyAndName("c-uuid", COMPANY, "Thomas"))
                .thenReturn(Optional.of(existing));

        EconomicsContactDto fresh1 = new EconomicsContactDto();
        fresh1.setCustomerContactNumber(777);
        fresh1.setObjectVersion("v1");
        EconomicsContactDto fresh2 = new EconomicsContactDto();
        fresh2.setCustomerContactNumber(777);
        fresh2.setObjectVersion("v2");
        when(api.getContact(777)).thenReturn(fresh1).thenReturn(fresh2);

        EconomicsContactDto updated = new EconomicsContactDto();
        updated.setCustomerContactNumber(777);
        updated.setObjectVersion("v-new");
        when(api.updateContact(eq(777), any()))
                .thenThrow(new WebApplicationException(Response.status(409).build()))
                .thenReturn(updated);

        service.syncContactToCompany(ct, billing, COMPANY);

        verify(api, times(2)).getContact(777);
        verify(api, times(2)).updateContact(eq(777), any());
    }

    // ----------------------- skip paths -----------------------

    @Test
    void skips_when_no_customer_pairing_without_calling_api() {
        Contract ct = makeContract("Thomas", "thomas@x.dk");
        Client billing = makeClient("c-uuid");
        when(customerRepo.findByClientAndCompany("c-uuid", COMPANY)).thenReturn(Optional.empty());

        service.syncContactToCompany(ct, billing, COMPANY);

        verify(api, never()).createContact(any());
        verify(api, never()).updateContact(eq(0), any());
        verify(contactRepo, never()).persist(any(ClientEconomicsContact.class));
    }

    @Test
    void skips_when_billing_attention_is_blank() {
        Contract ct = makeContract("   ", null);
        Client billing = makeClient("c-uuid");

        service.syncContactToCompany(ct, billing, COMPANY);

        verify(customerRepo, never()).findByClientAndCompany(any(), any());
        verify(api, never()).createContact(any());
    }

    // ----------------------- failure recording -----------------------

    @Test
    void non_conflict_error_records_failure_and_throws() {
        Contract ct = makeContract("Thomas", "thomas@x.dk");
        Client billing = makeClient("c-uuid");
        when(customerRepo.findByClientAndCompany("c-uuid", COMPANY))
                .thenReturn(Optional.of(makeCustomerMapping("c-uuid", COMPANY, 101)));
        when(contactRepo.findByClientCompanyAndName("c-uuid", COMPANY, "Thomas"))
                .thenReturn(Optional.empty());
        when(api.createContact(any()))
                .thenThrow(new WebApplicationException(Response.status(500).build()));
        when(failures.findByClientAndCompany("c-uuid", COMPANY)).thenReturn(Optional.empty());

        assertThrows(SyncFailedException.class,
                () -> service.syncContactToCompany(ct, billing, COMPANY));

        ArgumentCaptor<ClientEconomicsSyncFailure> cap =
                ArgumentCaptor.forClass(ClientEconomicsSyncFailure.class);
        verify(failures).persist(cap.capture());
        assertEquals("c-uuid", cap.getValue().getClientUuid());
        assertEquals(COMPANY, cap.getValue().getCompanyUuid());
        assertEquals(1, cap.getValue().getAttemptCount());
        assertTrue(cap.getValue().getLastError().contains("500"));
    }

    @Test
    void syncContactToAllCompanies_swallows_per_agreement_failures() {
        Contract ct = makeContract("Thomas", "thomas@x.dk");
        Client billing = makeClient("c-uuid");
        when(customerRepo.findByClientAndCompany("c-uuid", COMPANY))
                .thenReturn(Optional.of(makeCustomerMapping("c-uuid", COMPANY, 101)));
        when(contactRepo.findByClientCompanyAndName("c-uuid", COMPANY, "Thomas"))
                .thenReturn(Optional.empty());
        when(api.createContact(any()))
                .thenThrow(new WebApplicationException(Response.status(500).build()));

        // Must not throw.
        service.syncContactToAllCompanies(ct, billing);

        verify(failures).persist(any(ClientEconomicsSyncFailure.class));
    }

    // ----------------------- helpers -----------------------

    private static Contract makeContract(String attention, String email) {
        Contract ct = new Contract();
        ct.setBillingAttention(attention);
        ct.setBillingEmail(email);
        return ct;
    }

    private static Client makeClient(String uuid) {
        Client c = new Client();
        c.setUuid(uuid);
        c.setName("x");
        return c;
    }

    private static ClientEconomicsCustomer makeCustomerMapping(String clientUuid, String companyUuid, int customerNumber) {
        ClientEconomicsCustomer m = new ClientEconomicsCustomer();
        m.setUuid("m-" + clientUuid);
        m.setClientUuid(clientUuid);
        m.setCompanyUuid(companyUuid);
        m.setCustomerNumber(customerNumber);
        m.setPairingSource(PairingSource.MANUAL);
        return m;
    }
}
