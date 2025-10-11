package dk.trustworks.intranet.snapshot.strategy;

import java.util.Map;

/**
 * Strategy interface for entity-specific snapshot behavior.
 * Implementations provide serialization, validation, and metadata extraction
 * for specific entity types.
 * <p>
 * This pattern allows the generic snapshot system to handle different entity types
 * without coupling to specific business logic.
 * <p>
 * Example implementations:
 * - BonusPoolSnapshotStrategy - For FiscalYearPoolContext
 * - ContractSnapshotStrategy - For Contract entities
 * - FinancialReportSnapshotStrategy - For financial statements
 *
 * @param <T> the entity type this strategy handles
 */
public interface SnapshotStrategy<T> {

    /**
     * Get the entity type discriminator this strategy handles.
     * Must be unique across all registered strategies.
     * <p>
     * Examples: "bonus_pool", "contract", "financial_report"
     *
     * @return entity type string
     */
    String getEntityType();

    /**
     * Serialize entity to JSON string.
     * Implementation should handle all necessary conversions and formatting.
     * <p>
     * Typically uses Jackson ObjectMapper or similar JSON library.
     *
     * @param entity the entity to serialize
     * @return JSON string representation
     * @throws dk.trustworks.intranet.snapshot.exceptions.SnapshotException if serialization fails
     */
    String serializeToJson(T entity);

    /**
     * Deserialize JSON string to entity.
     * Implementation should reconstruct the entity from JSON.
     * <p>
     * Used when retrieving snapshots and converting back to domain objects.
     *
     * @param json the JSON string
     * @return deserialized entity
     * @throws dk.trustworks.intranet.snapshot.exceptions.SnapshotException if deserialization fails
     */
    T deserializeFromJson(String json);

    /**
     * Validate entity before creating snapshot.
     * Implementation should check business rules and data integrity.
     * <p>
     * Examples:
     * - Check required fields are not null
     * - Validate ID format
     * - Check business constraints
     * <p>
     * Throw exception if validation fails.
     *
     * @param entity the entity to validate
     * @throws jakarta.validation.ValidationException if validation fails
     */
    void validateBeforeSnapshot(T entity);

    /**
     * Extract metadata from entity for storage.
     * Metadata is stored as separate JSON field and can be queried without
     * deserializing the entire snapshot.
     * <p>
     * Examples:
     * - Fiscal year from bonus pool
     * - Contract number and parties from contract
     * - Reporting period from financial report
     * <p>
     * Returns map of key-value pairs that will be serialized to JSON.
     * Return empty map if no metadata needed.
     *
     * @param entity the entity to extract metadata from
     * @return metadata as key-value pairs
     */
    Map<String, String> extractMetadata(T entity);

    /**
     * Get the Java class this strategy handles.
     * Used for type checking and deserialization.
     * <p>
     * Default implementation returns Object.class.
     * Override to provide specific class for type safety.
     *
     * @return entity class
     */
    default Class<T> getEntityClass() {
        @SuppressWarnings("unchecked")
        Class<T> clazz = (Class<T>) Object.class;
        return clazz;
    }
}
