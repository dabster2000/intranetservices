package dk.trustworks.intranet.contracts.services;

import dk.trustworks.intranet.contracts.model.ContractTypeDefinition;
import dk.trustworks.intranet.contracts.model.enums.ContractType;
import jakarta.enterprise.context.ApplicationScoped;
import lombok.extern.jbosslog.JBossLog;

/**
 * Service for validating contract type codes.
 * Supports both legacy enum-based contract types and dynamic database-defined types.
 *
 * <p>Validation strategy:
 * <ol>
 *   <li>Check if the code matches a legacy ContractType enum value</li>
 *   <li>Check if the code exists in the contract_type_definitions table</li>
 *   <li>Return true if either condition is met</li>
 * </ol>
 */
@JBossLog
@ApplicationScoped
public class ContractTypeValidationService {

    /**
     * Validate if a contract type code is valid.
     * Checks both legacy enum values and database-defined types.
     *
     * @param contractTypeCode The contract type code to validate (e.g., "SKI0217_2025")
     * @return true if valid, false otherwise
     */
    public boolean isValidContractType(String contractTypeCode) {
        if (contractTypeCode == null || contractTypeCode.trim().isEmpty()) {
            log.debug("Contract type code is null or empty");
            return false;
        }

        String code = contractTypeCode.trim();

        // Check if it matches a legacy enum value
        if (isLegacyEnumValue(code)) {
            log.debug("Contract type " + code + " is a legacy enum value");
            return true;
        }

        // Check if it exists in the database
        if (existsInDatabase(code)) {
            log.debug("Contract type " + code + " exists in database");
            return true;
        }

        log.warn("Contract type " + code + " is not valid - not in enum or database");
        return false;
    }

    /**
     * Check if a contract type code matches a legacy ContractType enum value.
     *
     * @param code The contract type code
     * @return true if it matches an enum value
     */
    public boolean isLegacyEnumValue(String code) {
        try {
            ContractType.valueOf(code);
            return true;
        } catch (IllegalArgumentException e) {
            return false;
        }
    }

    /**
     * Check if a contract type code exists in the database.
     *
     * @param code The contract type code
     * @return true if it exists and is active
     */
    public boolean existsInDatabase(String code) {
        ContractTypeDefinition definition = ContractTypeDefinition.findByCode(code);
        return definition != null && definition.isActive();
    }

    /**
     * Get a user-friendly error message for an invalid contract type.
     *
     * @param contractTypeCode The invalid contract type code
     * @return Error message
     */
    public String getValidationErrorMessage(String contractTypeCode) {
        if (contractTypeCode == null || contractTypeCode.trim().isEmpty()) {
            return "Contract type code is required";
        }

        return "Invalid contract type '" + contractTypeCode + "'. " +
               "Must be either a valid legacy type (PERIOD, SKI0217_2021, SKI0217_2025, SKI0215_2025, SKI0217_2025_V2) " +
               "or an active contract type defined via the contract types API.";
    }
}
