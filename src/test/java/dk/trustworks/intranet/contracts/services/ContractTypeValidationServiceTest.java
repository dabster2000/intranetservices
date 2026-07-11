package dk.trustworks.intranet.contracts.services;

import dk.trustworks.intranet.contracts.exceptions.ContractValidationException.ErrorType;
import dk.trustworks.intranet.contracts.model.ContractTypeDefinition;
import dk.trustworks.intranet.contracts.model.enums.LifecycleStatus;
import io.quarkus.test.TestTransaction;
import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.security.TestSecurity;
import jakarta.inject.Inject;
import org.junit.jupiter.api.Test;

import java.time.LocalDate;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Agreement lifecycle classification and exclusive-end boundary coverage for G2. */
@QuarkusTest
@TestSecurity(user = "admin", roles = {"contracts:read", "contracts:write"})
class ContractTypeValidationServiceTest {

    @Inject
    ContractTypeValidationService service;

    @Test
    @TestTransaction
    void returnsStableCodesForRequiredMissingArchivedScheduledAndExpired() {
        var required = service.validate(" ", LocalDate.of(2026, 1, 1));
        assertEquals(ErrorType.CONTRACT_TYPE_REQUIRED, required.errorType());

        String missingCode = uniqueCode("MISS");
        var missing = service.validate(missingCode, LocalDate.of(2026, 1, 1));
        assertEquals(ErrorType.CONTRACT_TYPE_NOT_FOUND, missing.errorType());
        assertEquals("Agreement '" + missingCode + "' was not found.", missing.message());

        String archivedCode = uniqueCode("ARCH");
        persist(archivedCode, false, LocalDate.of(2020, 1, 1), LocalDate.of(2021, 1, 1));
        var archived = service.validate(archivedCode, LocalDate.of(2026, 1, 1));
        assertEquals(LifecycleStatus.ARCHIVED, archived.status(), "inactive wins over date status");
        assertEquals(ErrorType.CONTRACT_TYPE_ARCHIVED, archived.errorType());
        assertEquals("This contract's agreement is archived — restore it or change the agreement to save.",
                archived.message());

        String scheduledCode = uniqueCode("SCHED");
        persist(scheduledCode, true, LocalDate.of(2027, 1, 1), null);
        var scheduled = service.validate(scheduledCode, LocalDate.of(2026, 12, 31));
        assertEquals(ErrorType.CONTRACT_TYPE_NOT_YET_VALID, scheduled.errorType());
        assertEquals("This agreement starts on 1 Jan 2027 and is not available for this contract.",
                scheduled.message());

        String expiredCode = uniqueCode("EXP");
        persist(expiredCode, true, LocalDate.of(2025, 1, 1), LocalDate.of(2026, 1, 1));
        var expired = service.validate(expiredCode, LocalDate.of(2026, 1, 1));
        assertEquals(ErrorType.CONTRACT_TYPE_EXPIRED, expired.errorType());
        assertEquals("This agreement expired on 31 Dec 2025 and is not available for this contract.",
                expired.message());
    }

    @Test
    @TestTransaction
    void validFromIsInclusive_validUntilIsExclusive_andNullDateUsesCopenhagenToday() {
        String code = uniqueCode("BOUND");
        persist(code, true, LocalDate.of(2026, 1, 1), LocalDate.of(2027, 1, 1));

        assertTrue(service.validate(code, LocalDate.of(2026, 1, 1)).valid());
        assertTrue(service.validate(code, LocalDate.of(2026, 12, 31)).valid());
        assertEquals(ErrorType.CONTRACT_TYPE_EXPIRED,
                service.validate(code, LocalDate.of(2027, 1, 1)).errorType());

        String tomorrowCode = uniqueCode("TODAY");
        persist(tomorrowCode, true, LifecycleStatus.today().plusDays(1), null);
        assertEquals(ErrorType.CONTRACT_TYPE_NOT_YET_VALID,
                service.validate(tomorrowCode, null).errorType());
    }

    private static void persist(String code, boolean active, LocalDate from, LocalDate until) {
        ContractTypeDefinition definition = new ContractTypeDefinition();
        definition.setCode(code);
        definition.setName("Agreement " + code);
        definition.setActive(active);
        definition.setValidFrom(from);
        definition.setValidUntil(until);
        definition.persist();
    }

    private static String uniqueCode(String prefix) {
        return "ZZ" + prefix + Math.abs(System.nanoTime() % 1_000_000_000L);
    }
}
