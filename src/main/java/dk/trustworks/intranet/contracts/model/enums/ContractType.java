package dk.trustworks.intranet.contracts.model.enums;

/**
 * Legacy enum for contract types.
 *
 * @deprecated As of version 2.0.0, contract types are now managed dynamically via the
 *             {@link dk.trustworks.intranet.contracts.model.ContractTypeDefinition} entity
 *             and REST API endpoints.
 *             <p>
 *             This enum is kept for backward compatibility with existing hardcoded pricing rules
 *             and will continue to work alongside the new dynamic system.
 *             </p>
 *             <p>
 *             <b>Migration Guide:</b>
 *             <ul>
 *               <li>Use String contract type codes instead of this enum in new code</li>
 *               <li>For validation, use {@link dk.trustworks.intranet.contracts.services.ContractTypeValidationService}</li>
 *               <li>Create new contract types via POST /api/contract-types</li>
 *               <li>See docs/api-migration-contract-types.md for complete migration guide</li>
 *             </ul>
 *             </p>
 *
 * @see dk.trustworks.intranet.contracts.model.ContractTypeDefinition
 * @see dk.trustworks.intranet.contracts.services.ContractTypeValidationService
 * @see dk.trustworks.intranet.contracts.resources.ContractTypeResource
 */
@Deprecated(since = "2.0.0", forRemoval = false)
public enum ContractType {
    /**
     * Standard time and materials contract.
     */
    PERIOD,

    /**
     * SKI Framework Agreement 2021 with 2% admin fee and 2000 DKK fixed deduction.
     */
    SKI0217_2021,

    /**
     * SKI Framework Agreement 2025 with 4% admin fee.
     */
    SKI0217_2025,

    /**
     * SKI Simplified Agreement 2025 with 4% admin fee only.
     */
    SKI0215_2025,

    /**
     * SKI Framework Agreement 2025 V2 (alternative pricing).
     */
    SKI0217_2025_V2
}
