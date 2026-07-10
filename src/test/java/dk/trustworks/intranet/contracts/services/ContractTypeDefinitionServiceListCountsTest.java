package dk.trustworks.intranet.contracts.services;

import dk.trustworks.intranet.contracts.dto.ContractTypeDefinitionDTO;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * Unit tests for the C2 list enrichment merge: grouped per-code counts are
 * applied to every list item, with 0 (never null) for codes that have no
 * contracts / no active pricing rules. The grouped queries themselves are
 * exercised by the integration suite (they require a database).
 *
 * Plain JUnit — {@code applyListCounts} is a pure function.
 */
class ContractTypeDefinitionServiceListCountsTest {

    @Test
    void appliesCountsByCode() {
        ContractTypeDefinitionDTO ski = dto("SKI0217_2025");
        ContractTypeDefinitionDTO period = dto("PERIOD");

        ContractTypeDefinitionService.applyListCounts(
                List.of(ski, period),
                Map.of("SKI0217_2025", 42L, "PERIOD", 7L),
                Map.of("SKI0217_2025", 3L, "PERIOD", 1L));

        assertEquals(42, ski.getContractCount());
        assertEquals(3, ski.getActivePricingRuleCount());
        assertEquals(7, period.getContractCount());
        assertEquals(1, period.getActivePricingRuleCount());
    }

    @Test
    void missingCodesGetZero_neverNull() {
        ContractTypeDefinitionDTO unused = dto("NEW_UNUSED");

        ContractTypeDefinitionService.applyListCounts(
                List.of(unused),
                Map.of("OTHER_CODE", 5L),
                Map.of());

        assertEquals(0, unused.getContractCount());
        assertEquals(0, unused.getActivePricingRuleCount());
    }

    @Test
    void countsInOneMapDoNotLeakIntoTheOther() {
        ContractTypeDefinitionDTO dto = dto("SKI0215_2025_V2");

        ContractTypeDefinitionService.applyListCounts(
                List.of(dto),
                Map.of("SKI0215_2025_V2", 9L),
                Map.of("SKI0215_2025_V2", 2L));

        assertEquals(9, dto.getContractCount());
        assertEquals(2, dto.getActivePricingRuleCount());
    }

    @Test
    void emptyDtoListIsANoOp() {
        // Must not throw.
        ContractTypeDefinitionService.applyListCounts(List.of(), Map.of("X", 1L), Map.of("X", 1L));
    }

    private static ContractTypeDefinitionDTO dto(String code) {
        ContractTypeDefinitionDTO dto = new ContractTypeDefinitionDTO();
        dto.setCode(code);
        return dto;
    }
}
