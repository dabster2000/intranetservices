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
class ClientEconomicsContactRepositoryTest {

    @Inject
    ClientEconomicsContactRepository repo;

    @Test
    @TestTransaction
    void persists_and_queries_by_client_company_name() {
        ClientEconomicsContact row = new ClientEconomicsContact();
        row.setUuid(UUID.randomUUID().toString());
        row.setClientUuid("client-1");
        row.setCompanyUuid("co-1");
        row.setContactName("Thomas Vinther");
        row.setCustomerContactNumber(42);
        row.setObjectVersion("obj-1");
        row.setReceiveEInvoices(true);
        row.setEinvoiceId("EI-007");
        row.setSyncedAt(LocalDateTime.now());
        repo.persist(row);

        Optional<ClientEconomicsContact> found =
                repo.findByClientCompanyAndName("client-1", "co-1", "Thomas Vinther");
        assertTrue(found.isPresent());
        assertEquals(42, found.get().getCustomerContactNumber());
        assertTrue(found.get().isReceiveEInvoices());
        assertEquals("EI-007", found.get().getEinvoiceId());
    }

    @Test
    @TestTransaction
    void lists_by_client_and_company() {
        ClientEconomicsContact a = new ClientEconomicsContact();
        a.setUuid(UUID.randomUUID().toString());
        a.setClientUuid("client-2");
        a.setCompanyUuid("co-1");
        a.setContactName("Alice");
        a.setCustomerContactNumber(1);
        a.setSyncedAt(LocalDateTime.now());
        repo.persist(a);

        ClientEconomicsContact b = new ClientEconomicsContact();
        b.setUuid(UUID.randomUUID().toString());
        b.setClientUuid("client-2");
        b.setCompanyUuid("co-1");
        b.setContactName("Bob");
        b.setCustomerContactNumber(2);
        b.setSyncedAt(LocalDateTime.now());
        repo.persist(b);

        List<ClientEconomicsContact> list = repo.listByClientAndCompany("client-2", "co-1");
        assertEquals(2, list.size());
    }

    @Test
    @TestTransaction
    void returns_empty_when_missing() {
        assertTrue(repo.findByClientCompanyAndName("missing", "co-1", "Nobody").isEmpty());
    }
}
