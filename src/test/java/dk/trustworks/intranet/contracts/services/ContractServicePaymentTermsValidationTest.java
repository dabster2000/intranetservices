package dk.trustworks.intranet.contracts.services;

import dk.trustworks.intranet.aggregates.invoice.economics.PaymentTermsMapping;
import dk.trustworks.intranet.aggregates.invoice.economics.PaymentTermsMappingRepository;
import dk.trustworks.intranet.aggregates.invoice.economics.PaymentTermsType;
import dk.trustworks.intranet.contracts.model.Contract;
import dk.trustworks.intranet.contracts.model.enums.ContractStatus;
import dk.trustworks.intranet.model.Company;
import io.quarkus.test.TestTransaction;
import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.security.TestSecurity;
import jakarta.inject.Inject;
import jakarta.persistence.EntityManager;
import jakarta.ws.rs.BadRequestException;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

@QuarkusTest
@TestSecurity(user = "admin", roles = {"contracts:write", "contracts:read"})
class ContractServicePaymentTermsValidationTest {

    @Inject ContractService contractService;
    @Inject PaymentTermsMappingRepository ptmRepo;
    @Inject EntityManager em;

    @Test
    @TestTransaction
    void save_rejectsPaymentTermsFromDifferentCompany() {
        Company a = firstCompany(0);
        Company b = firstCompany(1);
        PaymentTermsMapping ptForB = newMapping(b);
        ptmRepo.persist(ptForB);

        Contract c = newDraftContract(a);
        c.setPaymentTermsUuid(ptForB.getUuid());

        BadRequestException ex = assertThrows(BadRequestException.class, () -> contractService.save(c));
        assertTrue(ex.getMessage().contains("Payment terms"));
        assertTrue(ex.getMessage().contains(b.getName()));
        assertTrue(ex.getMessage().contains(a.getName()));
    }

    @Test
    @TestTransaction
    void save_acceptsPaymentTermsFromSameCompany() {
        Company a = firstCompany(0);
        PaymentTermsMapping ptForA = newMapping(a);
        ptmRepo.persist(ptForA);

        Contract c = newDraftContract(a);
        c.setPaymentTermsUuid(ptForA.getUuid());
        assertDoesNotThrow(() -> contractService.save(c));
    }

    @Test
    @TestTransaction
    void save_acceptsNullPaymentTerms() {
        Contract c = newDraftContract(firstCompany(0));
        c.setPaymentTermsUuid(null);
        assertDoesNotThrow(() -> contractService.save(c));
    }

    @Test
    @TestTransaction
    void update_rejectsMismatchedPaymentTerms() {
        Company a = firstCompany(0);
        Company b = firstCompany(1);
        Contract c = newDraftContract(a);
        contractService.save(c);

        PaymentTermsMapping ptForB = newMapping(b);
        ptmRepo.persist(ptForB);
        c.setPaymentTermsUuid(ptForB.getUuid());

        assertThrows(BadRequestException.class, () -> contractService.update(c));
    }

    private Company firstCompany(int i) {
        List<Company> cs = em.createQuery("SELECT c FROM Company c ORDER BY c.uuid", Company.class).getResultList();
        return cs.get(i);
    }

    private PaymentTermsMapping newMapping(Company c) {
        PaymentTermsMapping m = new PaymentTermsMapping();
        m.setUuid(UUID.randomUUID().toString());
        m.setPaymentTermsType(PaymentTermsType.NET);
        m.setPaymentDays(30);
        m.setCompany(c);
        m.setEconomicsPaymentTermsNumber(99);
        return m;
    }

    private Contract newDraftContract(Company c) {
        Contract contract = new Contract();
        contract.setUuid(UUID.randomUUID().toString());
        contract.setCompany(c);
        contract.setContractType("PERIOD");
        contract.setStatus(ContractStatus.INACTIVE);
        contract.setName("test-" + UUID.randomUUID());
        return contract;
    }
}
