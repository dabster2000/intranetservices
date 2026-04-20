package dk.trustworks.intranet.aggregates.invoice.economics.customer;

import dk.trustworks.intranet.aggregates.invoice.economics.CustomerCreatedResult;
import dk.trustworks.intranet.aggregates.invoice.economics.customer.dto.AutoRunResultDto;
import dk.trustworks.intranet.aggregates.invoice.economics.customer.dto.PairingCandidateDto;
import dk.trustworks.intranet.aggregates.invoice.economics.customer.dto.PairingRequestDto;
import dk.trustworks.intranet.aggregates.invoice.economics.customer.dto.PairingRowDto;
import dk.trustworks.intranet.dao.crm.model.Client;
import dk.trustworks.intranet.dao.crm.model.enums.ClientType;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * Unit tests for {@link EconomicsCustomerPairingService}. Does not load the
 * Quarkus container — the service is pure orchestration over mockable
 * collaborators (repo, cache, api-client factory, client lookup).
 */
class EconomicsCustomerPairingServiceTest {

    // Matches Trustworks A/S companyuuid so the service's hardcoded
    // AgreementDefaults map (Phase G1.1) returns a non-null bundle during
    // createAndPair. Other tests here don't care about the specific UUID.
    private static final UUID COMPANY_UUID = UUID.fromString("d8894494-2fb4-4f72-9e05-e6032e6dd691");
    private static final String COMPANY = COMPANY_UUID.toString();
    private static final UUID CLIENT_UUID = UUID.fromString("00000000-0000-0000-0000-00000000000c");

    private ClientEconomicsCustomerRepository repo;
    private EconomicsCustomerIndexCache cache;
    private EconomicsCustomerApiClient api;
    private ClientLookup clientLookup;
    private AgreementResolver agreementResolver;
    private AgreementDefaultsRegistry agreementDefaults;
    private ClientToEconomicsCustomerMapper customerMapper;

    private EconomicsCustomerPairingService svc;

    @BeforeEach
    void setUp() {
        repo = mock(ClientEconomicsCustomerRepository.class);
        cache = mock(EconomicsCustomerIndexCache.class);
        api = mock(EconomicsCustomerApiClient.class);
        clientLookup = mock(ClientLookup.class);
        agreementResolver = mock(AgreementResolver.class);
        agreementDefaults = new AgreementDefaultsRegistry();
        // Real mapper (pure logic, no collaborators) so createAndPair emits
        // the full §6.3 payload — matching production behaviour.
        customerMapper = new ClientToEconomicsCustomerMapper();

        svc = new EconomicsCustomerPairingService(repo, cache, clientLookup,
                agreementResolver, agreementDefaults, customerMapper);

        // listPairingRows / autoRun now batch-load via listByCompany instead
        // of per-client findByClientAndCompany. Default to an empty list so
        // tests that don't care about pairings behave as before.
        when(repo.listByCompany(anyString())).thenReturn(List.of());
        // createAndPair consults the index for CVR collision checks; stub a
        // default empty index so tests that don't set one don't NPE.
        when(cache.getIndex(anyString())).thenReturn(new EconomicsCustomerIndex(List.of()));
    }

    // ----------------------- listPairingRows -----------------------

    @Test
    void listPairingRows_paired_client_returns_PAIRED_row_with_customer_number() {
        Client c = client("c1", "Acme A/S", "12345678", ClientType.CLIENT);
        when(clientLookup.listAll()).thenReturn(List.of(c));
        ClientEconomicsCustomer row = pairingRow("c1", COMPANY, 101, PairingSource.AUTO_CVR);
        when(repo.listByCompany(COMPANY)).thenReturn(List.of(row));
        when(cache.getIndex(COMPANY)).thenReturn(new EconomicsCustomerIndex(List.of()));

        List<PairingRowDto> out = svc.listPairingRows(COMPANY_UUID);

        assertEquals(1, out.size());
        PairingRowDto r = out.get(0);
        assertEquals("c1", r.getClientUuid());
        assertEquals("Acme A/S", r.getClientName());
        assertEquals("PAIRED", r.getPairingStatus());
        assertEquals(101, r.getEconomicsCustomerNumber());
        assertEquals(PairingSource.AUTO_CVR, r.getPairingSource());
    }

    @Test
    void listPairingRows_unmatched_when_index_has_no_candidates() {
        Client c = client("c1", "Nowhere", "99999999", ClientType.CLIENT);
        when(clientLookup.listAll()).thenReturn(List.of(c));
        when(repo.findByClientAndCompany("c1", COMPANY)).thenReturn(Optional.empty());
        when(cache.getIndex(COMPANY)).thenReturn(new EconomicsCustomerIndex(List.of()));

        List<PairingRowDto> out = svc.listPairingRows(COMPANY_UUID);

        assertEquals("UNMATCHED", out.get(0).getPairingStatus());
        assertNull(out.get(0).getEconomicsCustomerNumber());
        assertTrue(out.get(0).getCandidates().isEmpty());
    }

    @Test
    void listPairingRows_ambiguous_when_multiple_cvr_matches() {
        Client c = client("c1", "Double CVR", "11111111", ClientType.CLIENT);
        when(clientLookup.listAll()).thenReturn(List.of(c));
        when(repo.findByClientAndCompany("c1", COMPANY)).thenReturn(Optional.empty());
        EconomicsCustomerIndex idx = new EconomicsCustomerIndex(List.of(
                econCustomer(101, "Dup One", "11111111"),
                econCustomer(102, "Dup Two", "11111111")));
        when(cache.getIndex(COMPANY)).thenReturn(idx);

        List<PairingRowDto> out = svc.listPairingRows(COMPANY_UUID);

        assertEquals("AMBIGUOUS", out.get(0).getPairingStatus());
        assertEquals(2, out.get(0).getCandidates().size());
        assertTrue(out.get(0).getCandidates().stream().allMatch(pc -> pc.matchReason().equals("CVR")));
    }

    // ----------------------- autoRun -----------------------

    @Test
    void autoRun_pairs_by_cvr_then_by_name() {
        Client byCvr = client("c1", "Acme A/S", "12345678", ClientType.CLIENT);
        Client byName = client("c2", "Beta Ltd", null, ClientType.CLIENT);
        Client unmatched = client("c3", "Unknown", null, ClientType.CLIENT);
        when(clientLookup.listAll()).thenReturn(List.of(byCvr, byName, unmatched));
        when(repo.findByClientAndCompany(anyString(), eq(COMPANY))).thenReturn(Optional.empty());

        EconomicsCustomerIndex idx = new EconomicsCustomerIndex(List.of(
                econCustomer(101, "Acme A/S", "12345678"),
                econCustomer(202, "Beta Ltd", null)));
        when(cache.getIndex(COMPANY)).thenReturn(idx);

        AutoRunResultDto result = svc.autoRun(COMPANY_UUID);

        assertEquals(2, result.getPaired());
        assertEquals(1, result.getUnmatched());
        assertEquals(0, result.getAmbiguous());
        verify(repo, times(2)).persist(any(ClientEconomicsCustomer.class));
    }

    @Test
    void autoRun_keeps_ambiguous_as_ambiguous() {
        Client c = client("c1", "Doppelganger", "22222222", ClientType.CLIENT);
        when(clientLookup.listAll()).thenReturn(List.of(c));
        when(repo.findByClientAndCompany(anyString(), anyString())).thenReturn(Optional.empty());

        EconomicsCustomerIndex idx = new EconomicsCustomerIndex(List.of(
                econCustomer(101, "Doppel 1", "22222222"),
                econCustomer(102, "Doppel 2", "22222222")));
        when(cache.getIndex(COMPANY)).thenReturn(idx);

        AutoRunResultDto result = svc.autoRun(COMPANY_UUID);

        assertEquals(0, result.getPaired());
        assertEquals(1, result.getAmbiguous());
        verify(repo, never()).persist(any(ClientEconomicsCustomer.class));
    }

    @Test
    void autoRun_skips_already_paired() {
        Client c = client("c1", "Already Paired", "12345678", ClientType.CLIENT);
        when(clientLookup.listAll()).thenReturn(List.of(c));
        when(repo.listByCompany(COMPANY))
                .thenReturn(List.of(pairingRow("c1", COMPANY, 101, PairingSource.MANUAL)));
        when(cache.getIndex(COMPANY)).thenReturn(new EconomicsCustomerIndex(List.of()));

        AutoRunResultDto result = svc.autoRun(COMPANY_UUID);

        assertEquals(1, result.getUnchanged());
        verify(repo, never()).persist(any(ClientEconomicsCustomer.class));
    }

    // ----------------------- pairManually -----------------------

    @Test
    void pairManually_inserts_when_absent() {
        PairingRequestDto req = new PairingRequestDto();
        req.setClientUuid("c1");
        req.setCompanyUuid(COMPANY);
        req.setEconomicsCustomerNumber(42);
        req.setPairingSource(PairingSource.MANUAL);
        when(repo.findByClientAndCompany("c1", COMPANY)).thenReturn(Optional.empty());

        svc.pairManually(req);

        ArgumentCaptor<ClientEconomicsCustomer> cap = ArgumentCaptor.forClass(ClientEconomicsCustomer.class);
        verify(repo).persist(cap.capture());
        assertEquals(42, cap.getValue().getCustomerNumber());
        assertEquals(PairingSource.MANUAL, cap.getValue().getPairingSource());
        assertEquals("c1", cap.getValue().getClientUuid());
        assertEquals(COMPANY, cap.getValue().getCompanyUuid());
    }

    @Test
    void pairManually_upserts_when_present() {
        PairingRequestDto req = new PairingRequestDto();
        req.setClientUuid("c1");
        req.setCompanyUuid(COMPANY);
        req.setEconomicsCustomerNumber(99);
        req.setPairingSource(PairingSource.MANUAL);
        ClientEconomicsCustomer existing = pairingRow("c1", COMPANY, 42, PairingSource.AUTO_CVR);
        when(repo.findByClientAndCompany("c1", COMPANY)).thenReturn(Optional.of(existing));

        svc.pairManually(req);

        verify(repo).persist(existing);
        assertEquals(99, existing.getCustomerNumber());
        assertEquals(PairingSource.MANUAL, existing.getPairingSource());
    }

    // ----------------------- unpair -----------------------

    @Test
    void unpair_deletes_existing_row() {
        ClientEconomicsCustomer existing = pairingRow(CLIENT_UUID.toString(), COMPANY, 42, PairingSource.MANUAL);
        when(repo.findByClientAndCompany(CLIENT_UUID.toString(), COMPANY))
                .thenReturn(Optional.of(existing));

        svc.unpair(CLIENT_UUID, COMPANY_UUID);

        verify(repo).delete(existing);
    }

    @Test
    void unpair_noop_when_row_missing() {
        when(repo.findByClientAndCompany(anyString(), anyString())).thenReturn(Optional.empty());

        svc.unpair(CLIENT_UUID,
                COMPANY_UUID);

        verify(repo, never()).delete(any(ClientEconomicsCustomer.class));
    }

    // ----------------------- searchEconomicsCustomers -----------------------

    @Test
    void search_returns_candidates_by_name_and_cvr() {
        EconomicsCustomerIndex idx = new EconomicsCustomerIndex(List.of(
                econCustomer(101, "Acme A/S", "12345678"),
                econCustomer(102, "Ace Labs", "87654321"),
                econCustomer(103, "Other", "99999999")));
        when(cache.getIndex(COMPANY)).thenReturn(idx);

        // Name substring: "ac" matches Acme and Ace
        List<PairingCandidateDto> byName = svc.searchEconomicsCustomers(
                COMPANY_UUID, "ac");
        assertEquals(2, byName.size());

        // CVR exact match
        List<PairingCandidateDto> byCvr = svc.searchEconomicsCustomers(
                COMPANY_UUID, "99999999");
        assertEquals(1, byCvr.size());
        assertEquals(103, byCvr.get(0).customerNumber());
    }

    @Test
    void search_empty_query_returns_empty() {
        List<PairingCandidateDto> out = svc.searchEconomicsCustomers(
                COMPANY_UUID, "  ");
        assertTrue(out.isEmpty());
        verifyNoInteractions(cache);
    }

    // ----------------------- createAndPair -----------------------

    @Test
    void createAndPair_creates_customer_grants_access_and_persists_row() {
        Client c = client(CLIENT_UUID.toString(), "Acme A/S", "12345678", ClientType.CLIENT);
        when(clientLookup.findByUuid(CLIENT_UUID.toString())).thenReturn(Optional.of(c));
        when(agreementResolver.apiFor(COMPANY)).thenReturn(api);
        when(repo.findByClientAndCompany(CLIENT_UUID.toString(), COMPANY)).thenReturn(Optional.empty());

        CustomerCreatedResult createResult = new CustomerCreatedResult();
        createResult.setCustomerNumber(12345678);
        when(api.createCustomer(any(EconomicsCustomerDto.class))).thenReturn(createResult);

        // After POST, service does GET to get full DTO for the access PUT.
        EconomicsCustomerDto created = new EconomicsCustomerDto();
        created.setCustomerNumber(12345678);
        created.setName("Acme A/S");
        created.setCvrNo("12345678");
        when(api.getCustomer(12345678)).thenReturn(created);

        // updateCustomer is void — Mockito default does nothing. No stub needed.

        PairingRowDto row = svc.createAndPair(CLIENT_UUID, COMPANY_UUID);

        assertEquals(CLIENT_UUID.toString(), row.getClientUuid());
        assertEquals("PAIRED", row.getPairingStatus());
        assertEquals(12345678, row.getEconomicsCustomerNumber());
        assertEquals(PairingSource.CREATED, row.getPairingSource());
        verify(api).createCustomer(any(EconomicsCustomerDto.class));
        verify(api).updateCustomer(eq(12345678), any(EconomicsCustomerDto.class));
        verify(repo).persist(any(ClientEconomicsCustomer.class));
        verify(cache).invalidate(COMPANY);
    }

    @Test
    void createAndPair_unknown_client_throws() {
        UUID missing = UUID.fromString("00000000-0000-0000-0000-0000006d6973");
        when(clientLookup.findByUuid(missing.toString())).thenReturn(Optional.empty());
        assertThrows(
                IllegalArgumentException.class,
                () -> svc.createAndPair(missing, COMPANY_UUID));
    }

    // ----------------------- helpers -----------------------

    private static Client client(String uuid, String name, String cvr, ClientType type) {
        Client c = new Client();
        c.setUuid(uuid);
        c.setName(name);
        c.setCvr(cvr);
        c.setType(type);
        c.setActive(true);
        return c;
    }

    private static ClientEconomicsCustomer pairingRow(String clientUuid, String companyUuid, int number, PairingSource src) {
        ClientEconomicsCustomer row = new ClientEconomicsCustomer();
        row.setUuid(UUID.randomUUID().toString());
        row.setClientUuid(clientUuid);
        row.setCompanyUuid(companyUuid);
        row.setCustomerNumber(number);
        row.setPairingSource(src);
        return row;
    }

    private static EconomicsCustomerDto econCustomer(int number, String name, String cvr) {
        EconomicsCustomerDto d = new EconomicsCustomerDto();
        d.setCustomerNumber(number);
        d.setName(name);
        d.setCvrNo(cvr);
        return d;
    }
}
