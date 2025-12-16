package dk.trustworks.intranet.utils.services;

import dk.trustworks.intranet.documentservice.model.DocumentTemplateEntity;
import dk.trustworks.intranet.documentservice.model.TemplatePlaceholderEntity;
import dk.trustworks.intranet.documentservice.model.enums.FieldType;
import dk.trustworks.intranet.utils.NumberUtils;
import jakarta.enterprise.context.ApplicationScoped;
import lombok.extern.jbosslog.JBossLog;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * Service for formatting placeholder values based on their FieldType.
 * <p>
 * Applies type-aware formatting to placeholder values before they are
 * substituted into Word templates for PDF generation:
 * <ul>
 *   <li>CURRENCY: Danish format "kr. 40.000,00"</li>
 *   <li>DECIMAL: Danish format "40.000,00"</li>
 *   <li>Others: Pass through unchanged</li>
 * </ul>
 * </p>
 */
@JBossLog
@ApplicationScoped
public class PlaceholderFormattingService {

    /**
     * Formats placeholder values based on their FieldType from the template.
     * <p>
     * Looks up the template by UUID, retrieves its placeholders, and formats
     * each form value according to its declared FieldType.
     * </p>
     *
     * @param templateUuid UUID of the document template (optional - if null, returns formValues unchanged)
     * @param formValues   Key-value pairs of placeholder values to format
     * @return Formatted form values map, or original map if templateUuid is null or template not found
     */
    public Map<String, String> formatPlaceholderValues(String templateUuid, Map<String, String> formValues) {
        if (templateUuid == null || templateUuid.isBlank()) {
            log.debug("No templateUuid provided, skipping placeholder formatting");
            return formValues;
        }

        if (formValues == null || formValues.isEmpty()) {
            log.debug("No form values provided, skipping placeholder formatting");
            return formValues;
        }

        // Look up template by UUID
        DocumentTemplateEntity template = DocumentTemplateEntity.findById(templateUuid);
        if (template == null) {
            log.warnf("Template not found for UUID: %s, skipping placeholder formatting", templateUuid);
            return formValues;
        }

        // Get placeholders for the template
        List<TemplatePlaceholderEntity> placeholders = TemplatePlaceholderEntity.findByTemplate(template);
        if (placeholders.isEmpty()) {
            log.debugf("No placeholders found for template: %s, skipping formatting", templateUuid);
            return formValues;
        }

        // Build fieldType map: placeholderKey -> FieldType
        Map<String, FieldType> fieldTypes = placeholders.stream()
            .collect(Collectors.toMap(
                TemplatePlaceholderEntity::getPlaceholderKey,
                TemplatePlaceholderEntity::getFieldType,
                (existing, replacement) -> existing // Handle duplicates by keeping first
            ));

        log.infof("Formatting %d placeholder values using %d placeholder definitions from template %s",
            formValues.size(), fieldTypes.size(), templateUuid);

        // Format values based on type
        Map<String, String> formatted = new HashMap<>();
        for (Map.Entry<String, String> entry : formValues.entrySet()) {
            String key = entry.getKey();
            String value = entry.getValue();
            FieldType type = fieldTypes.get(key);

            String formattedValue = formatValue(key, value, type);
            formatted.put(key, formattedValue);
        }

        return formatted;
    }

    /**
     * Formats a single value based on its FieldType.
     *
     * @param key   Placeholder key (for logging)
     * @param value The raw value string
     * @param type  The field type (may be null if placeholder not found)
     * @return Formatted value, or original value if type is null or formatting fails
     */
    private String formatValue(String key, String value, FieldType type) {
        if (value == null || value.isBlank()) {
            return value;
        }

        if (type == null) {
            log.debugf("No FieldType found for placeholder '%s', returning original value", key);
            return value;
        }

        try {
            return switch (type) {
                case CURRENCY -> {
                    double currencyVal = Double.parseDouble(value);
                    String formatted = NumberUtils.formatCurrency(currencyVal);
                    log.debugf("Formatted CURRENCY placeholder '%s': %s -> %s", key, value, formatted);
                    yield formatted;
                }
                case DECIMAL -> {
                    double decimalVal = Double.parseDouble(value);
                    String formatted = NumberUtils.formatDouble(decimalVal);
                    log.debugf("Formatted DECIMAL placeholder '%s': %s -> %s", key, value, formatted);
                    yield formatted;
                }
                default -> value;
            };
        } catch (NumberFormatException e) {
            log.warnf("Failed to parse numeric value for placeholder '%s' (type: %s, value: '%s'): %s",
                key, type, value, e.getMessage());
            return value; // Return original value on parsing failure
        }
    }
}
