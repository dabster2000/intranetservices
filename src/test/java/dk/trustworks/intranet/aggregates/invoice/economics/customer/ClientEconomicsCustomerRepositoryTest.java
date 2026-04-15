package dk.trustworks.intranet.aggregates.invoice.economics.customer;

import io.quarkus.test.TestTransaction;
import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import org.junit.jupiter.api.Test;

import java.time.LocalDateTime;
import java.util.Optional;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

@QuarkusTest
class ClientEconomicsCustomerRepositoryTest {

    @Inject ClientEconomicsCustomerRepository repo;

    @Test
    @TestTransaction
    void persists_and_finds_by_client_and_company() {
        ClientEconomicsCustomer row = new ClientEconomicsCustomer();
        row.setUuid(UUID.randomUUID().toString());
        row.setClientUuid("client-abc");
        row.setCompanyUuid("company-1");
        row.setCustomerNumber(12345);
        row.setObjectVersion("obj-v-1");
        row.setPairingSource(PairingSource.AUTO_CVR);
        row.setSyncedAt(LocalDateTime.now());
        repo.persist(row);

        Optional<ClientEconomicsCustomer> found = repo.findByClientAndCompany("client-abc", "company-1");
        assertTrue(found.isPresent());
        assertEquals(12345, found.get().getCustomerNumber());
        assertEquals(PairingSource.AUTO_CVR, found.get().getPairingSource());
    }

    @Test
    @TestTransaction
    void returns_empty_when_missing() {
        assertTrue(repo.findByClientAndCompany("missing", "company-1").isEmpty());
    }
}
