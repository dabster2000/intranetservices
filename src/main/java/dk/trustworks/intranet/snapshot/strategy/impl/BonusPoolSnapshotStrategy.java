package dk.trustworks.intranet.snapshot.strategy.impl;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import dk.trustworks.intranet.snapshot.exceptions.SnapshotException;
import dk.trustworks.intranet.snapshot.strategy.SnapshotStrategy;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.validation.ValidationException;

import java.util.HashMap;
import java.util.Map;

/**
 * Snapshot strategy for bonus pool data.
 * Handles JSON serialization/deserialization and validation for fiscal year bonus pools.
 * <p>
 * This strategy works with raw JSON strings representing FiscalYearPoolContext data.
 * The JSON structure includes:
 * - Fiscal year
 * - Leader point data
 * - Utilization metrics
 * - Revenue figures
 * - Bonus calculations
 */
@ApplicationScoped
public class BonusPoolSnapshotStrategy implements SnapshotStrategy<String> {

    public static final String ENTITY_TYPE = "bonus_pool";

    @Inject
    ObjectMapper objectMapper;

    @Override
    public String getEntityType() {
        return ENTITY_TYPE;
    }

    @Override
    public String serializeToJson(String entity) {
        // Validate that it's valid JSON first
        try {
            objectMapper.readTree(entity);
            return entity;
        } catch (JsonProcessingException e) {
            throw new SnapshotException("Invalid JSON for bonus pool snapshot", e);
        }
    }

    @Override
    public String deserializeFromJson(String json) {
        // Validate JSON structure before returning
        try {
            objectMapper.readTree(json);
            return json;
        } catch (JsonProcessingException e) {
            throw new SnapshotException("Failed to deserialize bonus pool snapshot", e);
        }
    }

    @Override
    public void validateBeforeSnapshot(String entity) {
        if (entity == null || entity.trim().isEmpty()) {
            throw new ValidationException("Bonus pool data cannot be null or empty");
        }

        // Validate JSON structure
        try {
            JsonNode root = objectMapper.readTree(entity);

            // Check for required fields (basic validation)
            if (!root.has("fiscalYear") && !root.has("fiscal_year")) {
                throw new ValidationException(
                    "Bonus pool JSON must contain 'fiscalYear' field");
            }

            // Validate fiscal year if present
            JsonNode fiscalYearNode = root.has("fiscalYear") ?
                root.get("fiscalYear") : root.get("fiscal_year");

            if (fiscalYearNode != null && !fiscalYearNode.isInt()) {
                throw new ValidationException(
                    "Fiscal year must be an integer");
            }

        } catch (JsonProcessingException e) {
            throw new ValidationException("Invalid JSON structure for bonus pool", e);
        }
    }

    @Override
    public Map<String, String> extractMetadata(String entity) {
        Map<String, String> metadata = new HashMap<>();

        try {
            JsonNode root = objectMapper.readTree(entity);

            // Extract fiscal year
            JsonNode fiscalYearNode = root.has("fiscalYear") ?
                root.get("fiscalYear") : root.get("fiscal_year");
            if (fiscalYearNode != null) {
                metadata.put("fiscalYear", fiscalYearNode.asText());
            }

            // Extract pool size if available
            if (root.has("poolSize")) {
                metadata.put("poolSize", root.get("poolSize").asText());
            }

            // Extract total revenue if available
            if (root.has("totalRevenue")) {
                metadata.put("totalRevenue", root.get("totalRevenue").asText());
            }

            // Add snapshot creation date
            metadata.put("snapshotDate", java.time.LocalDate.now().toString());

        } catch (JsonProcessingException e) {
            // If metadata extraction fails, return empty map
            // Don't fail the snapshot creation just because metadata can't be extracted
            io.quarkus.logging.Log.warnf("Failed to extract metadata from bonus pool: %s",
                e.getMessage());
        }

        return metadata;
    }

    @Override
    public Class<String> getEntityClass() {
        return String.class;
    }
}
