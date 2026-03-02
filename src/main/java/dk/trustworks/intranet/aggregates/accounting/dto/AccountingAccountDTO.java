package dk.trustworks.intranet.aggregates.accounting.dto;

import dk.trustworks.intranet.financeservice.model.enums.CostType;

/**
 * Read-only projection of an accounting account for the admin API.
 * Includes company and category context without exposing financial amounts.
 */
public record AccountingAccountDTO(
        String uuid,
        int accountCode,
        String accountDescription,
        boolean shared,
        boolean salary,
        CostType costType,
        String companyUuid,
        String companyName,
        String categoryUuid,
        String categoryName
) {}
