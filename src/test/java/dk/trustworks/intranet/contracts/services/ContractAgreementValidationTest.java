package dk.trustworks.intranet.contracts.services;

import dk.trustworks.intranet.contracts.exceptions.ContractValidationException;
import dk.trustworks.intranet.contracts.exceptions.ContractValidationException.ErrorType;
import dk.trustworks.intranet.contracts.model.Contract;
import dk.trustworks.intranet.contracts.model.ContractTypeDefinition;
import dk.trustworks.intranet.contracts.model.enums.ContractStatus;
import dk.trustworks.intranet.model.Company;
import io.quarkus.test.TestTransaction;
import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.security.TestSecurity;
import jakarta.inject.Inject;
import jakarta.persistence.EntityManager;
import org.junit.jupiter.api.Test;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.UUID;
import java.util.function.Supplier;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

/** ContractService integration: grandfathering, archived blocking, and changed-type persistence. */
@QuarkusTest
@TestSecurity(user = "admin", roles = {"contracts:read", "contracts:write"})
class ContractAgreementValidationTest {

    @Inject ContractService contractService;
    @Inject EntityManager entityManager;

    @Test
    @TestTransaction
    void createMapsEveryAgreementFailureToStructuredErrorCode() {
        Contract required = contract(null, LocalDateTime.now());
        assertError(ErrorType.CONTRACT_TYPE_REQUIRED, () -> contractService.save(required));

        Contract missing = contract("ZZMISSING123", LocalDateTime.now());
        assertError(ErrorType.CONTRACT_TYPE_NOT_FOUND, () -> contractService.save(missing));

        String archivedCode = persistDefinition(false, null, null);
        assertError(ErrorType.CONTRACT_TYPE_ARCHIVED,
                () -> contractService.save(contract(archivedCode, LocalDateTime.now())));

        String scheduledCode = persistDefinition(true, LocalDate.now().plusDays(1), null);
        assertError(ErrorType.CONTRACT_TYPE_NOT_YET_VALID,
                () -> contractService.save(contract(scheduledCode, LocalDateTime.now())));

        String expiredCode = persistDefinition(true, LocalDate.now().minusYears(1), LocalDate.now());
        assertError(ErrorType.CONTRACT_TYPE_EXPIRED,
                () -> contractService.save(contract(expiredCode, LocalDateTime.now())));
    }

    @Test
    @TestTransaction
    void expiredAgreementIsGrandfatheredAgainstOriginalCreationDate() {
        String code = persistDefinition(true, LocalDate.of(2020, 1, 1), LocalDate.of(2021, 1, 1));
        Contract contract = contract(code, LocalDateTime.of(2020, 6, 1, 12, 0));

        assertDoesNotThrow(() -> contractService.save(contract));
        contract.setNote("grandfathered update");
        assertDoesNotThrow(() -> contractService.update(contract));
    }

    @Test
    @TestTransaction
    void unchangedExpiredAgreementWithMissingLegacyCreatedDateIsGrandfathered() {
        String code = persistDefinition(true, LocalDate.of(2020, 1, 1), LocalDate.of(2021, 1, 1));
        Contract contract = contract(code, null);
        contract.persist();
        entityManager.flush();
        entityManager.detach(contract); // Mirror the detached request entity used by the REST update path.

        contract.setNote("legacy null-created update");
        assertDoesNotThrow(() -> contractService.update(contract));

        String otherExpired = persistDefinition(true, LocalDate.of(2020, 1, 1), LocalDate.of(2021, 1, 1));
        contract.setContractType(otherExpired);
        assertError(ErrorType.CONTRACT_TYPE_EXPIRED, () -> {
            contractService.update(contract);
            return contract;
        });
    }

    @Test
    @TestTransaction
    void archivedAgreementBlocksUpdateWithStructuredCode() {
        String code = persistDefinition(true, null, null);
        Contract contract = contract(code, LocalDateTime.now());
        contractService.save(contract);

        ContractTypeDefinition definition = ContractTypeDefinition.findByCode(code);
        definition.setActive(false);
        entityManager.flush();

        ContractValidationException exception = assertThrows(
                ContractValidationException.class, () -> contractService.update(contract));
        assertEquals(ErrorType.CONTRACT_TYPE_ARCHIVED, exception.getErrors().get(0).getType());
        assertEquals("contractType", exception.getErrors().get(0).getField());
    }

    @Test
    @TestTransaction
    void updatePersistsChangedAgreementCode() {
        String oldCode = persistDefinition(true, null, null);
        String newCode = persistDefinition(true, null, null);
        Contract contract = contract(oldCode, LocalDateTime.now());
        contractService.save(contract);

        contract.setContractType(newCode);
        contractService.update(contract);
        entityManager.flush();
        entityManager.clear();

        Contract reloaded = Contract.findById(contract.getUuid());
        assertEquals(newCode, reloaded.getContractType());
    }

    @Test
    @TestTransaction
    void extensionRevalidatesAgreementOnNewContractCreationDate() {
        String code = persistDefinition(true, null, null);
        Contract source = contract(code, LocalDateTime.now());
        contractService.save(source);

        ContractTypeDefinition definition = ContractTypeDefinition.findByCode(code);
        definition.setValidFrom(LocalDate.now().minusYears(1));
        definition.setValidUntil(LocalDate.now());
        entityManager.flush();

        assertError(ErrorType.CONTRACT_TYPE_EXPIRED, () -> contractService.extendContract(source.getUuid()));
    }

    @Test
    @TestTransaction
    void extensionRejectsArchivedAgreement() {
        String code = persistDefinition(true, null, null);
        Contract source = contract(code, LocalDateTime.now());
        contractService.save(source);

        ContractTypeDefinition definition = ContractTypeDefinition.findByCode(code);
        definition.setActive(false);
        entityManager.flush();

        assertError(ErrorType.CONTRACT_TYPE_ARCHIVED, () -> contractService.extendContract(source.getUuid()));
    }

    private Contract contract(String code, LocalDateTime created) {
        Company company = entityManager.createQuery("SELECT c FROM Company c ORDER BY c.uuid", Company.class)
                .setMaxResults(1)
                .getSingleResult();
        Contract contract = new Contract();
        contract.setUuid(UUID.randomUUID().toString());
        contract.setCompany(company);
        String clientUuid = UUID.randomUUID().toString();
        entityManager.createNativeQuery("INSERT INTO client(uuid, name) VALUES (?1, 'Agreement Test Client')")
                .setParameter(1, clientUuid)
                .executeUpdate();
        contract.setClientuuid(clientUuid);
        contract.setContractType(code);
        contract.setStatus(ContractStatus.INACTIVE);
        contract.setName("agreement-validation-test");
        contract.setCreated(created);
        return contract;
    }

    private static String persistDefinition(boolean active, LocalDate from, LocalDate until) {
        String code = "ZZAGREE" + Math.abs(System.nanoTime() % 1_000_000_000L);
        ContractTypeDefinition definition = new ContractTypeDefinition();
        definition.setCode(code);
        definition.setName("Agreement " + code);
        definition.setActive(active);
        definition.setValidFrom(from);
        definition.setValidUntil(until);
        definition.persist();
        return code;
    }

    private static void assertError(ErrorType expected, Supplier<?> operation) {
        ContractValidationException exception = assertThrows(
                ContractValidationException.class, operation::get);
        assertEquals(expected, exception.getErrors().get(0).getType());
        assertEquals("contractType", exception.getErrors().get(0).getField());
    }
}
