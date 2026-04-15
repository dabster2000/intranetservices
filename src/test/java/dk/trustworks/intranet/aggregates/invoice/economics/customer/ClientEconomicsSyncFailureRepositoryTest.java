package dk.trustworks.intranet.aggregates.invoice.economics.customer;

import io.quarkus.test.TestTransaction;
import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import org.junit.jupiter.api.Test;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

@QuarkusTest
class ClientEconomicsSyncFailureRepositoryTest {

    @Inject
    ClientEconomicsSyncFailureRepository repo;

    @Test
    @TestTransaction
    void persists_and_queries_by_client_and_company() {
        ClientEconomicsSyncFailure row = new ClientEconomicsSyncFailure();
        row.setUuid(UUID.randomUUID().toString());
        row.setClientUuid("client-x");
        row.setCompanyUuid("co-1");
        row.setAttemptCount(1);
        row.setLastError("Timeout from e-conomic");
        row.setNextRetryAt(LocalDateTime.now().plusMinutes(5));
        row.setLastAttemptedAt(LocalDateTime.now());
        repo.persist(row);

        Optional<ClientEconomicsSyncFailure> found = repo.findByClientAndCompany("client-x", "co-1");
        assertTrue(found.isPresent());
        assertEquals(1, found.get().getAttemptCount());
        assertEquals("PENDING", found.get().getStatus());
        assertNotNull(found.get().getCreatedAt());
    }

    @Test
    @TestTransaction
    void lists_entries_due_for_retry_before_now() {
        LocalDateTime now = LocalDateTime.now();

        ClientEconomicsSyncFailure due = new ClientEconomicsSyncFailure();
        due.setUuid(UUID.randomUUID().toString());
        due.setClientUuid("client-due");
        due.setCompanyUuid("co-1");
        due.setAttemptCount(2);
        due.setNextRetryAt(now.minusMinutes(1));
        repo.persist(due);

        ClientEconomicsSyncFailure future = new ClientEconomicsSyncFailure();
        future.setUuid(UUID.randomUUID().toString());
        future.setClientUuid("client-future");
        future.setCompanyUuid("co-1");
        future.setAttemptCount(1);
        future.setNextRetryAt(now.plusHours(1));
        repo.persist(future);

        ClientEconomicsSyncFailure abandoned = new ClientEconomicsSyncFailure();
        abandoned.setUuid(UUID.randomUUID().toString());
        abandoned.setClientUuid("client-abandoned");
        abandoned.setCompanyUuid("co-1");
        abandoned.setAttemptCount(99);
        abandoned.setNextRetryAt(now.minusHours(1));
        abandoned.setStatus("ABANDONED");
        repo.persist(abandoned);

        List<ClientEconomicsSyncFailure> list = repo.listDueForRetry(now);
        assertEquals(1, list.size());
        assertEquals("client-due", list.get(0).getClientUuid());
    }

    @Test
    @TestTransaction
    void lists_pending_by_company() {
        LocalDateTime now = LocalDateTime.now();

        ClientEconomicsSyncFailure pending = new ClientEconomicsSyncFailure();
        pending.setUuid(UUID.randomUUID().toString());
        pending.setClientUuid("client-pending");
        pending.setCompanyUuid("co-42");
        pending.setAttemptCount(1);
        pending.setNextRetryAt(now.plusMinutes(10));
        repo.persist(pending);

        ClientEconomicsSyncFailure other = new ClientEconomicsSyncFailure();
        other.setUuid(UUID.randomUUID().toString());
        other.setClientUuid("client-other");
        other.setCompanyUuid("co-99");
        other.setAttemptCount(1);
        other.setNextRetryAt(now.plusMinutes(10));
        repo.persist(other);

        List<ClientEconomicsSyncFailure> list = repo.listPendingByCompany("co-42");
        assertEquals(1, list.size());
        assertEquals("client-pending", list.get(0).getClientUuid());
    }

    @Test
    @TestTransaction
    void returns_empty_when_missing() {
        assertTrue(repo.findByClientAndCompany("missing", "co-1").isEmpty());
    }
}
