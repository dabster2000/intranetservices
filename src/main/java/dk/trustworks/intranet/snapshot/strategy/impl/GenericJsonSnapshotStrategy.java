package dk.trustworks.intranet.snapshot.strategy.impl;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import dk.trustworks.intranet.snapshot.exceptions.SnapshotException;
import dk.trustworks.intranet.snapshot.strategy.SnapshotStrategy;
import io.quarkus.logging.Log;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.validation.ValidationException;

import java.time.LocalDateTime;
import java.util.HashMap;
import java.util.Map;

/**
 * Generic fallback snapshot strategy for entity types without specific strategies.
 * <p>
 * This strategy provides basic JSON validation and allows the snapshot system to work
 * with any entity type, even if a specific strategy hasn't been implemented yet.
 * <p>
 * <strong>Warning:</strong> This is a fallback with minimal validation. It's recommended
 * to implement specific SnapshotStrategy classes for each entity type to ensure proper
 * business validation and metadata extraction.
 * <p>
 * When used, this strategy logs warnings to encourage proper strategy implementation.
 */
@ApplicationScoped
public class GenericJsonSnapshotStrategy implements SnapshotStrategy<String> {

    /**
     * Special entity type marker for the fallback strategy.
     * This is not registered as a normal strategy - it's used by the registry
     * when no specific strategy is found.
     */
    public static final String FALLBACK_ENTITY_TYPE = "*";

    @Inject
    ObjectMapper objectMapper;

    @Override
    public String getEntityType() {
        return FALLBACK_ENTITY_TYPE;
    }

    @Override
    public String serializeToJson(String entity) {
        // Validate that it's valid JSON
        try {
            objectMapper.readTree(entity);
            return entity;
        } catch (JsonProcessingException e) {
            throw new SnapshotException("Invalid JSON for generic snapshot", e);
        }
    }

    @Override
    public String deserializeFromJson(String json) {
        // Validate JSON structure before returning
        try {
            objectMapper.readTree(json);
            return json;
        } catch (JsonProcessingException e) {
            throw new SnapshotException("Failed to deserialize generic snapshot", e);
        }
    }

    @Override
    public void validateBeforeSnapshot(String entity) {
        if (entity == null || entity.trim().isEmpty()) {
            throw new ValidationException("Snapshot data cannot be null or empty");
        }

        // Validate JSON structure
        try {
            JsonNode root = objectMapper.readTree(entity);

            // Ensure it's a valid JSON object or array
            if (!root.isObject() && !root.isArray()) {
                throw new ValidationException(
                    "Snapshot data must be a JSON object or array");
            }

        } catch (JsonProcessingException e) {
            throw new ValidationException("Invalid JSON structure for snapshot", e);
        }
    }

    @Override
    public Map<String, String> extractMetadata(String entity) {
        Map<String, String> metadata = new HashMap<>();

        try {
            JsonNode root = objectMapper.readTree(entity);

            // Extract some common fields if they exist
            extractIfPresent(metadata, root, "id");
            extractIfPresent(metadata, root, "name");
            extractIfPresent(metadata, root, "type");
            extractIfPresent(metadata, root, "status");
            extractIfPresent(metadata, root, "fiscalYear");
            extractIfPresent(metadata, root, "year");

            // Add snapshot creation date
            metadata.put("snapshotDate", LocalDateTime.now().toString());
            metadata.put("usingFallbackStrategy", "true");

        } catch (JsonProcessingException e) {
            // If metadata extraction fails, return minimal metadata
            // Don't fail the snapshot creation just because metadata can't be extracted
            Log.warnf("Failed to extract metadata from generic snapshot: %s",
                e.getMessage());
            metadata.put("snapshotDate", LocalDateTime.now().toString());
            metadata.put("usingFallbackStrategy", "true");
            metadata.put("metadataExtractionFailed", "true");
        }

        return metadata;
    }

    /**
     * Helper method to extract a field from JSON if it exists.
     */
    private void extractIfPresent(Map<String, String> metadata, JsonNode root, String fieldName) {
        if (root.has(fieldName)) {
            JsonNode fieldNode = root.get(fieldName);
            if (!fieldNode.isNull()) {
                metadata.put(fieldName, fieldNode.asText());
            }
        }
    }

    @Override
    public Class<String> getEntityClass() {
        return String.class;
    }

    /**
     * Log a warning about using the generic fallback strategy.
     * Called by the registry when this strategy is used.
     *
     * @param entityType the entity type that's missing a specific strategy
     */
    public void logFallbackUsage(String entityType) {
        Log.warnf("Using generic fallback strategy for entity type '%s'. " +
                "Consider implementing SnapshotStrategy<%s> for proper validation and metadata extraction. " +
                "See BonusPoolSnapshotStrategy as an example.",
            entityType, entityType);
    }
}
