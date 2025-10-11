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
 * Snapshot strategy for partner bonus data.
 * Handles JSON serialization/deserialization and validation for partner bonus calculations.
 * <p>
 * The JSON structure includes:
 * - Fiscal year and period information
 * - Partner groups and their eligibility
 * - Consultant bonus details
 * - Group analytics and trends
 * - Summary statistics
 */
@ApplicationScoped
public class PartnerBonusSnapshotStrategy implements SnapshotStrategy<String> {

    public static final String ENTITY_TYPE = "partner_bonus";

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
            throw new SnapshotException("Invalid JSON for partner bonus snapshot", e);
        }
    }

    @Override
    public String deserializeFromJson(String json) {
        // Validate JSON structure before returning
        try {
            objectMapper.readTree(json);
            return json;
        } catch (JsonProcessingException e) {
            throw new SnapshotException("Failed to deserialize partner bonus snapshot", e);
        }
    }

    @Override
    public void validateBeforeSnapshot(String entity) {
        if (entity == null || entity.trim().isEmpty()) {
            throw new ValidationException("Partner bonus data cannot be null or empty");
        }

        // Validate JSON structure
        try {
            JsonNode root = objectMapper.readTree(entity);

            // Check for required top-level fields
            if (!root.has("fiscalYear") && !root.has("fiscal_year")) {
                throw new ValidationException(
                    "Partner bonus JSON must contain 'fiscalYear' field");
            }

            // Validate fiscal year if present
            JsonNode fiscalYearNode = root.has("fiscalYear") ?
                root.get("fiscalYear") : root.get("fiscal_year");

            if (fiscalYearNode != null && !fiscalYearNode.isInt()) {
                throw new ValidationException(
                    "Fiscal year must be an integer");
            }

            // Validate summary section exists
            if (!root.has("summary")) {
                throw new ValidationException(
                    "Partner bonus JSON must contain 'summary' section");
            }

            JsonNode summary = root.get("summary");
            if (!summary.isObject()) {
                throw new ValidationException(
                    "Partner bonus 'summary' must be an object");
            }

            // Validate consultants array exists
            if (!root.has("consultants")) {
                throw new ValidationException(
                    "Partner bonus JSON must contain 'consultants' array");
            }

            JsonNode consultants = root.get("consultants");
            if (!consultants.isArray()) {
                throw new ValidationException(
                    "Partner bonus 'consultants' must be an array");
            }

            // Validate groups array exists
            if (!root.has("groups")) {
                throw new ValidationException(
                    "Partner bonus JSON must contain 'groups' array");
            }

            JsonNode groups = root.get("groups");
            if (!groups.isArray()) {
                throw new ValidationException(
                    "Partner bonus 'groups' must be an array");
            }

        } catch (JsonProcessingException e) {
            throw new ValidationException("Invalid JSON structure for partner bonus", e);
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

            // Extract summary statistics
            if (root.has("summary")) {
                JsonNode summary = root.get("summary");

                extractSummaryField(metadata, summary, "totalPool");
                extractSummaryField(metadata, summary, "approvedTotal");
                extractSummaryField(metadata, summary, "pendingTotal");
                extractSummaryField(metadata, summary, "rejectedTotal");
                extractSummaryField(metadata, summary, "activeConsultants");
                extractSummaryField(metadata, summary, "totalInvoices");
                extractSummaryField(metadata, summary, "averageBonus");
                extractSummaryField(metadata, summary, "approvalRate");
                extractSummaryField(metadata, summary, "completionRate");
                extractSummaryField(metadata, summary, "healthy");
                extractSummaryField(metadata, summary, "dataIntegrityValid");
            }

            // Extract consultant count
            if (root.has("consultants")) {
                JsonNode consultants = root.get("consultants");
                metadata.put("consultantCount", String.valueOf(consultants.size()));
            }

            // Extract group count
            if (root.has("groups")) {
                JsonNode groups = root.get("groups");
                metadata.put("groupCount", String.valueOf(groups.size()));
            }

            // Extract fiscal year period
            if (root.has("fiscalYearStart")) {
                metadata.put("fiscalYearStart", root.get("fiscalYearStart").asText());
            }
            if (root.has("fiscalYearEnd")) {
                metadata.put("fiscalYearEnd", root.get("fiscalYearEnd").asText());
            }

            // Add snapshot date
            if (root.has("dataTimestamp")) {
                metadata.put("dataTimestamp", root.get("dataTimestamp").asText());
            }

            metadata.put("snapshotDate", java.time.LocalDate.now().toString());

        } catch (JsonProcessingException e) {
            // If metadata extraction fails, return minimal metadata
            // Don't fail the snapshot creation just because metadata can't be extracted
            io.quarkus.logging.Log.warnf("Failed to extract metadata from partner bonus: %s",
                e.getMessage());
            metadata.put("snapshotDate", java.time.LocalDate.now().toString());
            metadata.put("metadataExtractionFailed", "true");
        }

        return metadata;
    }

    /**
     * Helper method to extract a field from summary section if it exists.
     */
    private void extractSummaryField(Map<String, String> metadata, JsonNode summary, String fieldName) {
        if (summary.has(fieldName)) {
            JsonNode fieldNode = summary.get(fieldName);
            if (!fieldNode.isNull()) {
                metadata.put(fieldName, fieldNode.asText());
            }
        }
    }

    @Override
    public Class<String> getEntityClass() {
        return String.class;
    }
}
