package dk.trustworks.intranet.aggregates.invoice.economics;

import io.quarkus.test.TestTransaction;
import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import jakarta.persistence.EntityManager;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

@QuarkusTest
class VatZoneMappingRepositoryTest {

    @Inject VatZoneMappingRepository repo;
    @Inject EntityManager em;

    @Test
    @TestTransaction
    void listForCompany_returnsOnlyRowsForThatCompany() {
        String a = existingCompanyUuid(0);
        String b = existingCompanyUuid(1);
        insert(a, "DKK", 1);
        insert(b, "DKK", 2);

        List<VatZoneMapping> rows = repo.listForCompany(a);
        assertTrue(rows.stream().allMatch(r -> r.getCompany().getUuid().equals(a)));
        assertTrue(rows.stream().noneMatch(r -> r.getCompany().getUuid().equals(b)));
    }

    @Test
    @TestTransaction
    void findByCurrency_returnsCompanyScopedRow() {
        String a = existingCompanyUuid(0);
        insert(a, "DKK", 7);
        var found = repo.findByCurrency("DKK", a);
        assertTrue(found.isPresent());
        assertEquals(7, found.get().getEconomicsVatZoneNumber());
        assertEquals(a, found.get().getCompany().getUuid());
    }

    @Test
    @TestTransaction
    void findByCurrency_nullCompanyIsIllegal() {
        assertThrows(IllegalArgumentException.class, () -> repo.findByCurrency("DKK", null));
    }

    private String existingCompanyUuid(int index) {
        return em.createQuery("SELECT c.uuid FROM Company c ORDER BY c.uuid", String.class)
                .getResultList().get(index);
    }

    private void insert(String companyUuid, String currency, int zone) {
        VatZoneMapping m = new VatZoneMapping();
        m.setUuid(UUID.randomUUID().toString());
        m.setCurrency(currency);
        m.setCompany(em.getReference(dk.trustworks.intranet.model.Company.class, companyUuid));
        m.setEconomicsVatZoneNumber(zone);
        m.setVatRatePercent(new BigDecimal("25.00"));
        repo.persist(m);
    }
}
