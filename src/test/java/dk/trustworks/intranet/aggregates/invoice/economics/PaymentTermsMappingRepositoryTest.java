package dk.trustworks.intranet.aggregates.invoice.economics;

import io.quarkus.test.TestTransaction;
import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import jakarta.persistence.EntityManager;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

@QuarkusTest
class PaymentTermsMappingRepositoryTest {

    @Inject PaymentTermsMappingRepository repo;
    @Inject EntityManager em;

    @Test
    @TestTransaction
    void listForCompany_returnsOnlyRowsForThatCompany() {
        String companyA = existingCompanyUuid(0);
        String companyB = existingCompanyUuid(1);
        insertRow(companyA, PaymentTermsType.NET, 30, 5);
        insertRow(companyB, PaymentTermsType.NET, 30, 5);

        List<PaymentTermsMapping> rows = repo.listForCompany(companyA);
        assertTrue(rows.stream().allMatch(r -> r.getCompany().getUuid().equals(companyA)));
        assertTrue(rows.stream().noneMatch(r -> r.getCompany().getUuid().equals(companyB)));
    }

    @Test
    @TestTransaction
    void listForCompany_nullArgumentIsIllegal() {
        assertThrows(IllegalArgumentException.class, () -> repo.listForCompany(null));
    }

    @Test
    @TestTransaction
    void findByTypeAndDays_returnsRowScopedToCompany() {
        String companyA = existingCompanyUuid(0);
        insertRow(companyA, PaymentTermsType.NET, 30, 5);
        var found = repo.findByTypeAndDays(PaymentTermsType.NET, 30, companyA);
        assertTrue(found.isPresent());
        assertEquals(companyA, found.get().getCompany().getUuid());
    }

    private String existingCompanyUuid(int index) {
        List<String> uuids = em.createQuery("SELECT c.uuid FROM Company c ORDER BY c.uuid", String.class)
                .getResultList();
        return uuids.get(index);
    }

    private void insertRow(String companyUuid, PaymentTermsType type, Integer days, int econNumber) {
        PaymentTermsMapping m = new PaymentTermsMapping();
        m.setUuid(UUID.randomUUID().toString());
        m.setPaymentTermsType(type);
        m.setPaymentDays(days);
        m.setCompany(em.getReference(dk.trustworks.intranet.model.Company.class, companyUuid));
        m.setEconomicsPaymentTermsNumber(econNumber);
        repo.persist(m);
    }
}
