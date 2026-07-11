package dk.trustworks.intranet.contracts.services;

import dk.trustworks.intranet.contracts.model.ContractTypeDefinition;
import dk.trustworks.intranet.contracts.model.enums.ContractStatus;
import dk.trustworks.intranet.contracts.model.enums.LifecycleStatus;
import io.quarkus.test.TestTransaction;
import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.security.TestSecurity;
import jakarta.inject.Inject;
import jakarta.persistence.EntityManager;
import jakarta.ws.rs.BadRequestException;
import jakarta.ws.rs.NotFoundException;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

/** Projection/order/revenue coverage for GET /api/contract-types/{code}/contracts. */
@QuarkusTest
@TestSecurity(user = "reader", roles = {"contracts:read"})
class ContractTypeContractQueryServiceTest {

    @Inject ContractTypeContractQueryService service;
    @Inject EntityManager entityManager;

    @Test
    @TestTransaction
    void returnsAllStatusesParametersAndDeterministicRevenueTopTen() {
        String code = persistDefinition();
        String clientUuid = UUID.randomUUID().toString();
        entityManager.createNativeQuery("INSERT INTO client(uuid, name) VALUES (?1, 'Acme Client')")
                .setParameter(1, clientUuid)
                .executeUpdate();

        String alpha = insertContract(code, clientUuid, "Alpha", "SIGNED");
        String beta = insertContract(code, clientUuid, "Beta", "TIME");
        String gamma = insertContract(code, clientUuid, "Gamma", "CLOSED");
        entityManager.createNativeQuery(
                        "INSERT INTO contract_type_items(contractuuid, name, value) VALUES (?1, 'trapperabat', '7.5')")
                .setParameter(1, beta)
                .executeUpdate();

        insertInvoice(alpha, "CREATED", "INVOICE", LifecycleStatus.today(), new BigDecimal("1000.00"));
        insertInvoice(beta, "CREATED", "INVOICE", LifecycleStatus.today(), new BigDecimal("1000.00"));
        insertInvoice(gamma, "CREATED", "INVOICE", LifecycleStatus.today(), new BigDecimal("2000.00"));
        insertInvoice(gamma, "DRAFT", "INVOICE", LifecycleStatus.today(), new BigDecimal("9000.00"));
        insertInvoice(gamma, "CREATED", "CREDIT_NOTE", LifecycleStatus.today(), new BigDecimal("9000.00"));
        insertInvoice(gamma, "CREATED", "INVOICE",
                LifecycleStatus.today().minusMonths(12).minusDays(1), new BigDecimal("9000.00"));

        var response = service.findContracts(code);

        assertEquals(code, response.contractTypeCode());
        assertEquals(3, response.totalCount());
        assertEquals("Alpha", response.contracts().get(0).name());
        assertEquals(ContractStatus.SIGNED, response.contracts().get(0).status());
        assertEquals("Beta", response.contracts().get(1).name());
        assertEquals("Acme Client", response.contracts().get(1).clientName());
        assertEquals("trapperabat", response.contracts().get(1).parameters().get(0).key());
        assertEquals("7.5", response.contracts().get(1).parameters().get(0).value());
        assertEquals(ContractStatus.CLOSED, response.contracts().get(2).status());

        assertEquals(3, response.topContractsByRevenue().size());
        assertEquals("Gamma", response.topContractsByRevenue().get(0).name());
        assertEquals(0, new BigDecimal("2000.00").compareTo(
                response.topContractsByRevenue().get(0).revenueLast12Months()));
        assertEquals("Alpha", response.topContractsByRevenue().get(1).name(),
                "equal revenue ties sort by name then uuid");
        assertEquals("Beta", response.topContractsByRevenue().get(2).name());
    }

    @Test
    @TestTransaction
    void validatesCodeAndReturnsNotFoundForWellFormedUnknownCode() {
        assertThrows(BadRequestException.class, () -> service.findContracts("../../bad"));
        assertThrows(NotFoundException.class, () -> service.findContracts("ZZUNKNOWN123"));
    }

    private String persistDefinition() {
        String code = "ZZQUERY" + Math.abs(System.nanoTime() % 1_000_000_000L);
        ContractTypeDefinition definition = new ContractTypeDefinition();
        definition.setCode(code);
        definition.setName("Query test");
        definition.persist();
        return code;
    }

    private String insertContract(String code, String clientUuid, String name, String status) {
        String uuid = UUID.randomUUID().toString();
        entityManager.createNativeQuery(
                        "INSERT INTO contracts(uuid, contracttype, clientuuid, status, name) "
                                + "VALUES (?1, ?2, ?3, ?4, ?5)")
                .setParameter(1, uuid)
                .setParameter(2, code)
                .setParameter(3, clientUuid)
                .setParameter(4, status)
                .setParameter(5, name)
                .executeUpdate();
        return uuid;
    }

    private void insertInvoice(String contractUuid, String status, String type,
                               LocalDate invoiceDate, BigDecimal revenue) {
        String invoiceUuid = UUID.randomUUID().toString();
        entityManager.createNativeQuery(
                        "INSERT INTO invoices(uuid, contractuuid, status, type, invoicedate) "
                                + "VALUES (?1, ?2, ?3, ?4, ?5)")
                .setParameter(1, invoiceUuid)
                .setParameter(2, contractUuid)
                .setParameter(3, status)
                .setParameter(4, type)
                .setParameter(5, invoiceDate)
                .executeUpdate();
        entityManager.createNativeQuery(
                        "INSERT INTO invoiceitems(uuid, invoiceuuid, hours, rate, origin) "
                                + "VALUES (?1, ?2, 1, ?3, 'BASE')")
                .setParameter(1, UUID.randomUUID().toString())
                .setParameter(2, invoiceUuid)
                .setParameter(3, revenue)
                .executeUpdate();
    }
}
