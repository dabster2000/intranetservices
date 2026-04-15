package dk.trustworks.intranet.aggregates.invoice.economics.customer;

import dk.trustworks.intranet.dao.crm.model.Client;
import dk.trustworks.intranet.dao.crm.services.ClientService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.LocalDateTime;
import java.util.List;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Unit tests for {@link EconomicsCustomerSyncRetryBatchlet}. Plain JUnit +
 * Mockito — no Quarkus boot required.
 *
 * SPEC-INV-001 §6.8, §7.1 Phase G2.
 */
class EconomicsCustomerSyncRetryBatchletTest {

    private static final String COMPANY_A = "d8894494-2fb4-4f72-9e05-e6032e6dd691";
    private static final String COMPANY_B = "00000000-0000-0000-0000-000000000002";

    private ClientEconomicsSyncFailureRepository failures;
    private ClientService clientService;
    private EconomicsCustomerSyncService syncService;

    private EconomicsCustomerSyncRetryBatchlet batchlet;

    @BeforeEach
    void setUp() {
        failures = mock(ClientEconomicsSyncFailureRepository.class);
        clientService = mock(ClientService.class);
        syncService = mock(EconomicsCustomerSyncService.class);

        batchlet = new EconomicsCustomerSyncRetryBatchlet();
        batchlet.failures = failures;
        batchlet.clientService = clientService;
        batchlet.syncService = syncService;
    }

    @Test
    void invokes_sync_for_every_due_row() {
        Client client = makeClient("c-uuid");
        ClientEconomicsSyncFailure f1 = makeFailure("c-uuid", COMPANY_A);
        ClientEconomicsSyncFailure f2 = makeFailure("c-uuid", COMPANY_B);
        when(failures.listDueForRetry(any(LocalDateTime.class))).thenReturn(List.of(f1, f2));
        when(clientService.findByUuid("c-uuid")).thenReturn(client);

        batchlet.retryDueFailures();

        verify(syncService).syncToCompany(client, COMPANY_A);
        verify(syncService).syncToCompany(client, COMPANY_B);
    }

    @Test
    void no_due_rows_means_no_sync_calls() {
        when(failures.listDueForRetry(any(LocalDateTime.class))).thenReturn(List.of());

        batchlet.retryDueFailures();

        verify(syncService, never()).syncToCompany(any(), any());
        verify(clientService, never()).findByUuid(any());
    }

    @Test
    void deletes_failure_row_when_client_no_longer_exists() {
        ClientEconomicsSyncFailure f = makeFailure("ghost-uuid", COMPANY_A);
        when(failures.listDueForRetry(any(LocalDateTime.class))).thenReturn(List.of(f));
        when(clientService.findByUuid("ghost-uuid")).thenReturn(null);

        batchlet.retryDueFailures();

        verify(failures).delete(f);
        verify(syncService, never()).syncToCompany(any(), any());
    }

    @Test
    void one_failure_does_not_derail_subsequent_rows() {
        Client clientA = makeClient("c-a");
        Client clientB = makeClient("c-b");
        ClientEconomicsSyncFailure fA = makeFailure("c-a", COMPANY_A);
        ClientEconomicsSyncFailure fB = makeFailure("c-b", COMPANY_A);
        when(failures.listDueForRetry(any(LocalDateTime.class))).thenReturn(List.of(fA, fB));
        when(clientService.findByUuid("c-a")).thenReturn(clientA);
        when(clientService.findByUuid("c-b")).thenReturn(clientB);

        // First retry throws — the service already persisted the updated failure
        // row, so the batchlet must continue to the next due row.
        doThrow(new SyncFailedException("boom", new RuntimeException()))
                .when(syncService).syncToCompany(eq(clientA), eq(COMPANY_A));

        batchlet.retryDueFailures();

        verify(syncService).syncToCompany(clientA, COMPANY_A);
        verify(syncService).syncToCompany(clientB, COMPANY_A);
    }

    @Test
    void unexpected_runtime_error_also_does_not_break_the_loop() {
        Client clientA = makeClient("c-a");
        Client clientB = makeClient("c-b");
        ClientEconomicsSyncFailure fA = makeFailure("c-a", COMPANY_A);
        ClientEconomicsSyncFailure fB = makeFailure("c-b", COMPANY_A);
        when(failures.listDueForRetry(any(LocalDateTime.class))).thenReturn(List.of(fA, fB));
        when(clientService.findByUuid("c-a")).thenReturn(clientA);
        when(clientService.findByUuid("c-b")).thenReturn(clientB);

        doThrow(new RuntimeException("db hiccup"))
                .when(syncService).syncToCompany(eq(clientA), eq(COMPANY_A));

        batchlet.retryDueFailures();

        verify(syncService).syncToCompany(clientB, COMPANY_A);
    }

    // --------------------------------------------------- helpers

    private static Client makeClient(String uuid) {
        Client c = new Client();
        c.setUuid(uuid);
        c.setName("Test " + uuid);
        return c;
    }

    private static ClientEconomicsSyncFailure makeFailure(String clientUuid, String companyUuid) {
        ClientEconomicsSyncFailure f = new ClientEconomicsSyncFailure();
        f.setUuid("f-" + clientUuid + "-" + companyUuid);
        f.setClientUuid(clientUuid);
        f.setCompanyUuid(companyUuid);
        f.setAttemptCount(1);
        f.setStatus("PENDING");
        f.setNextRetryAt(LocalDateTime.now().minusMinutes(1));
        return f;
    }
}
