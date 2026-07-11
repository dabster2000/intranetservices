package dk.trustworks.intranet.contracts.services;

import dk.trustworks.intranet.aggregates.invoice.pricing.RuleStepType;
import dk.trustworks.intranet.aggregates.invoice.pricing.StepBase;
import dk.trustworks.intranet.contracts.model.ContractTypeDefinition;
import dk.trustworks.intranet.contracts.model.ContractTypeItem;
import dk.trustworks.intranet.contracts.model.PricingRuleStepEntity;
import io.quarkus.test.TestTransaction;
import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.security.TestSecurity;
import jakarta.inject.Inject;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceException;
import jakarta.ws.rs.BadRequestException;
import org.junit.jupiter.api.Test;

import java.time.LocalDateTime;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

/** G3 contract-parameter CRUD validation, ownership, ordering, and DB race guard. */
@QuarkusTest
@TestSecurity(user = "admin", roles = {"contracts:read", "contracts:write"})
class ContractTypeItemServiceTest {

    @Inject ContractService service;
    @Inject EntityManager entityManager;

    @Test
    @TestTransaction
    void crudRoundTrip_isSorted_andValidatesReferencedKeys() {
        Fixture fixture = fixture();

        var beta = service.addContractTypeItem(fixture.contractUuid(), item(" beta ", "2.5"));
        var alpha = service.addContractTypeItem(fixture.contractUuid(), item("ALPHA", "1"));

        var listed = service.getContractTypeItems(fixture.contractUuid());
        assertEquals(2, listed.size());
        assertEquals("alpha", listed.get(0).key());
        assertEquals("beta", listed.get(1).key());

        ContractTypeItem update = item("alpha", "3.75");
        update.setId(alpha.id());
        assertEquals("3.75", service.updateContractTypeItem(fixture.contractUuid(), update).value());

        service.deleteContractTypeItem(fixture.contractUuid(), beta.id());
        assertEquals(1, service.getContractTypeItems(fixture.contractUuid()).size());
        Number auditCount = (Number) entityManager.createNativeQuery(
                        "SELECT COUNT(*) FROM client_activity_log "
                                + "WHERE entity_uuid = ?1 AND field_name LIKE 'agreementParameter:%'")
                .setParameter(1, fixture.contractUuid())
                .getSingleResult();
        assertEquals(4L, auditCount.longValue(), "create/create/update/delete are all attributable");

        assertThrows(BadRequestException.class,
                () -> service.addContractTypeItem(fixture.contractUuid(), item("not-a-rule-key", "1")));
    }

    @Test
    @TestTransaction
    void rejectsDuplicateBlankNonDecimalAndCrossContractOwnership() {
        Fixture fixture = fixture();
        String otherContract = insertContract(fixture.code(), "other-contract");
        var existing = service.addContractTypeItem(fixture.contractUuid(), item("alpha", "1"));

        assertThrows(BadRequestException.class,
                () -> service.addContractTypeItem(fixture.contractUuid(), item("alpha", "2")));
        assertThrows(BadRequestException.class,
                () -> service.addContractTypeItem(fixture.contractUuid(), item(" ", "2")));
        assertThrows(BadRequestException.class,
                () -> service.addContractTypeItem(fixture.contractUuid(), item("x".repeat(256), "2")));
        assertThrows(BadRequestException.class,
                () -> service.addContractTypeItem(fixture.contractUuid(), item("beta", "NaN")));
        assertThrows(BadRequestException.class,
                () -> service.addContractTypeItem(fixture.contractUuid(), item("beta", "Infinity")));
        assertThrows(BadRequestException.class,
                () -> service.addContractTypeItem(fixture.contractUuid(), item("beta", "-0.01")));
        assertThrows(BadRequestException.class,
                () -> service.addContractTypeItem(fixture.contractUuid(), item("beta", "100.01")));
        assertThrows(BadRequestException.class,
                () -> service.addContractTypeItem(fixture.contractUuid(), item("beta", " ")));
        assertThrows(BadRequestException.class,
                () -> service.addContractTypeItem(fixture.contractUuid(), item("beta", "1".repeat(256))));

        ContractTypeItem crossContract = item("alpha", "5");
        crossContract.setId(existing.id());
        assertThrows(BadRequestException.class,
                () -> service.updateContractTypeItem(otherContract, crossContract));

        ContractTypeItem spoofedOwner = item("beta", "5");
        spoofedOwner.setContractuuid(otherContract);
        assertThrows(BadRequestException.class,
                () -> service.addContractTypeItem(fixture.contractUuid(), spoofedOwner));
    }

    @Test
    @TestTransaction
    void databaseUniqueIndexClosesDuplicateRace() {
        Fixture fixture = fixture();
        entityManager.createNativeQuery(
                        "INSERT INTO contract_type_items(contractuuid, name, value) VALUES (?1, 'alpha', '1')")
                .setParameter(1, fixture.contractUuid())
                .executeUpdate();

        assertThrows(PersistenceException.class, () -> entityManager.createNativeQuery(
                        "INSERT INTO contract_type_items(contractuuid, name, value) VALUES (?1, 'alpha', '2')")
                .setParameter(1, fixture.contractUuid())
                .executeUpdate());
    }

    private Fixture fixture() {
        String code = "ZZPARAM" + Math.abs(System.nanoTime() % 1_000_000_000L);
        ContractTypeDefinition definition = new ContractTypeDefinition();
        definition.setCode(code);
        definition.setName("Parameter test");
        definition.persist();
        persistParamRule(code, "alpha", 10);
        persistParamRule(code, "beta", 20);
        return new Fixture(code, insertContract(code, "parameter-contract"));
    }

    private String insertContract(String code, String name) {
        String uuid = UUID.randomUUID().toString();
        String clientUuid = UUID.randomUUID().toString();
        entityManager.createNativeQuery("INSERT INTO client(uuid, name) VALUES (?1, ?2)")
                .setParameter(1, clientUuid)
                .setParameter(2, name + " client")
                .executeUpdate();
        entityManager.createNativeQuery(
                        "INSERT INTO contracts(uuid, contracttype, clientuuid, status, name, created) "
                                + "VALUES (?1, ?2, ?3, 'INACTIVE', ?4, ?5)")
                .setParameter(1, uuid)
                .setParameter(2, code)
                .setParameter(3, clientUuid)
                .setParameter(4, name)
                .setParameter(5, LocalDateTime.now())
                .executeUpdate();
        return uuid;
    }

    private static void persistParamRule(String code, String key, int priority) {
        PricingRuleStepEntity rule = new PricingRuleStepEntity();
        rule.setContractTypeCode(code);
        rule.setRuleId("param-" + key);
        rule.setLabel("Parameter " + key);
        rule.setRuleStepType(RuleStepType.PERCENT_DISCOUNT_ON_SUM);
        rule.setStepBase(StepBase.CURRENT_SUM);
        rule.setParamKey(key);
        rule.setPriority(priority);
        rule.persist();
    }

    private static ContractTypeItem item(String key, String value) {
        ContractTypeItem item = new ContractTypeItem();
        item.setKey(key);
        item.setValue(value);
        return item;
    }

    private record Fixture(String code, String contractUuid) {
    }
}
