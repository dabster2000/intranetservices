package dk.trustworks.intranet.aggregates.invoice.economics.customer;

import dk.trustworks.intranet.aggregates.invoice.economics.customer.dto.ClientSyncStatusDto;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Unit tests for {@link EconomicsCustomerSyncService#statusFor(String)}.
 * Asserts the OK / PENDING / ABANDONED / UNPAIRED status resolution. Plain
 * JUnit + Mockito — no Quarkus boot required.
 *
 * <p>The {@link dk.trustworks.intranet.model.Company#findById} static lookup
 * returns null outside the Quarkus container; the service falls back to
 * {@code companyUuid} for the name, which is what these tests assert.
 *
 * <p><b>Never index into the returned list.</b> {@code statusFor} emits one row
 * per entry in {@link AgreementDefaultsRegistry#TEMPLATES}, which is a
 * {@code Map.of(...)}. With more than one entry that is an
 * {@code ImmutableCollections.MapN}, whose iteration order is randomised per JVM
 * from a salt seeded at class-init time — so {@code rows.get(0)} is a different
 * company on different runs of the same code. These tests stub the repositories
 * for {@link #COMPANY} only, so on the runs where another agreement sorted first
 * they read an unstubbed row and saw UNPAIRED. That is the whole of the
 * "non-deterministic economics test" this class was known for: it failed 4 tests
 * when A/S did not sort first and 1 when it did, on identical code. Select the
 * row by {@code companyUuid} instead — see {@link #rowFor(List)}.
 *
 * SPEC-INV-001 §7.1 Phase G2, §8.6.
 */
class EconomicsCustomerSyncServiceStatusForTest {

    /** Matches Trustworks A/S in the AgreementDefaultsRegistry. */
    private static final String COMPANY = "d8894494-2fb4-4f72-9e05-e6032e6dd691";

    private ClientEconomicsCustomerRepository repo;
    private ClientEconomicsSyncFailureRepository failures;
    private EconomicsCustomerSyncService service;

    /** The row for {@link #COMPANY}, regardless of where the map iteration put it. */
    private static ClientSyncStatusDto rowFor(List<ClientSyncStatusDto> rows) {
        return rows.stream()
                .filter(r -> COMPANY.equals(r.companyUuid()))
                .findFirst()
                .orElseThrow(() -> new AssertionError(
                        "statusFor returned no row for the configured agreement " + COMPANY));
    }

    @BeforeEach
    void setUp() {
        repo = mock(ClientEconomicsCustomerRepository.class);
        failures = mock(ClientEconomicsSyncFailureRepository.class);
        AgreementResolver agreementResolver = mock(AgreementResolver.class);
        AgreementDefaultsRegistry defaults = new AgreementDefaultsRegistry();
        ClientToEconomicsCustomerMapper mapper = new ClientToEconomicsCustomerMapper();
        SyncFailureRecorder failureRecorder = mock(SyncFailureRecorder.class);
        EconomicsCustomerIndexCache indexCache = mock(EconomicsCustomerIndexCache.class);
        service = new EconomicsCustomerSyncService(
                repo, failures, failureRecorder, agreementResolver,
                defaults, mapper, indexCache);
    }

    @Test
    void unpaired_when_no_pairing_and_no_failure() {
        when(repo.findByClientAndCompany("c-uuid", COMPANY)).thenReturn(Optional.empty());
        when(failures.findByClientAndCompany("c-uuid", COMPANY)).thenReturn(Optional.empty());

        List<ClientSyncStatusDto> rows = service.statusFor("c-uuid");

        // Derived from the registry rather than hardcoded: this assertion read
        // `1` from when the registry held a single agreement, and silently
        // became wrong when Technology and Cyber Security were added.
        assertEquals(AgreementDefaultsRegistry.TEMPLATES.size(), rows.size(),
                "One row per configured agreement");
        ClientSyncStatusDto row = rowFor(rows);
        assertEquals(ClientSyncStatusDto.STATUS_UNPAIRED, row.status());
        assertEquals(0, row.attemptCount());
        assertNull(row.lastError());
        assertNull(row.nextRetryAt());
        assertNull(row.lastAttemptedAt());
        assertEquals("c-uuid", row.clientUuid());
        assertEquals(COMPANY, row.companyUuid());
        assertNotNull(row.companyName(), "companyName must not be null (falls back to uuid)");
    }

    @Test
    void ok_when_pairing_exists_and_no_failure() {
        ClientEconomicsCustomer pairing = new ClientEconomicsCustomer();
        pairing.setClientUuid("c-uuid");
        pairing.setCompanyUuid(COMPANY);
        pairing.setCustomerNumber(101);
        pairing.setObjectVersion("v1");
        pairing.setPairingSource(PairingSource.CREATED);
        pairing.setSyncedAt(LocalDateTime.now());

        when(repo.findByClientAndCompany("c-uuid", COMPANY)).thenReturn(Optional.of(pairing));
        when(failures.findByClientAndCompany("c-uuid", COMPANY)).thenReturn(Optional.empty());

        List<ClientSyncStatusDto> rows = service.statusFor("c-uuid");

        ClientSyncStatusDto row = rowFor(rows);
        assertEquals(ClientSyncStatusDto.STATUS_OK, row.status());
        assertEquals(0, row.attemptCount());
        assertNull(row.nextRetryAt());
    }

    @Test
    void pending_when_failure_row_has_status_PENDING() {
        ClientEconomicsSyncFailure failure = new ClientEconomicsSyncFailure();
        failure.setClientUuid("c-uuid");
        failure.setCompanyUuid(COMPANY);
        failure.setAttemptCount(2);
        failure.setLastError("HTTP 500: Internal Server Error");
        failure.setNextRetryAt(LocalDateTime.now().plusMinutes(5));
        failure.setLastAttemptedAt(LocalDateTime.now().minusMinutes(1));
        failure.setStatus("PENDING");

        when(repo.findByClientAndCompany("c-uuid", COMPANY)).thenReturn(Optional.empty());
        when(failures.findByClientAndCompany("c-uuid", COMPANY)).thenReturn(Optional.of(failure));

        List<ClientSyncStatusDto> rows = service.statusFor("c-uuid");

        ClientSyncStatusDto row = rowFor(rows);
        assertEquals(ClientSyncStatusDto.STATUS_PENDING, row.status());
        assertEquals(2, row.attemptCount());
        assertEquals("HTTP 500: Internal Server Error", row.lastError());
        assertNotNull(row.nextRetryAt(), "PENDING must expose nextRetryAt");
        assertNotNull(row.lastAttemptedAt());
    }

    @Test
    void abandoned_overrides_pairing_even_when_both_exist() {
        // Pairing exists from a prior run. A subsequent edit has failed 6 times
        // and been marked ABANDONED — the badge must surface the failure.
        ClientEconomicsCustomer pairing = new ClientEconomicsCustomer();
        pairing.setClientUuid("c-uuid");
        pairing.setCompanyUuid(COMPANY);
        pairing.setCustomerNumber(101);
        pairing.setObjectVersion("v1");
        pairing.setPairingSource(PairingSource.CREATED);
        pairing.setSyncedAt(LocalDateTime.now().minusDays(2));

        ClientEconomicsSyncFailure failure = new ClientEconomicsSyncFailure();
        failure.setClientUuid("c-uuid");
        failure.setCompanyUuid(COMPANY);
        failure.setAttemptCount(6);
        failure.setLastError("HTTP 400: validation failed");
        failure.setNextRetryAt(LocalDateTime.now().plusDays(1));
        failure.setLastAttemptedAt(LocalDateTime.now().minusMinutes(30));
        failure.setStatus("ABANDONED");

        when(repo.findByClientAndCompany("c-uuid", COMPANY)).thenReturn(Optional.of(pairing));
        when(failures.findByClientAndCompany("c-uuid", COMPANY)).thenReturn(Optional.of(failure));

        List<ClientSyncStatusDto> rows = service.statusFor("c-uuid");

        ClientSyncStatusDto row = rowFor(rows);
        assertEquals(ClientSyncStatusDto.STATUS_ABANDONED, row.status(),
                "ABANDONED failure must override prior OK pairing");
        assertEquals(6, row.attemptCount());
        assertEquals("HTTP 400: validation failed", row.lastError());
        assertNull(row.nextRetryAt(),
                "nextRetryAt is only populated while PENDING — ABANDONED rows must not show a retry timer");
    }

    @Test
    void null_client_uuid_throws_NPE() {
        assertThrows(NullPointerException.class, () -> service.statusFor(null));
    }
}
