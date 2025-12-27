package dk.trustworks.intranet.sales.model.dto;

/**
 * DTO for requesting AI-assisted description suggestions for sales leads.
 *
 * Used by the AI suggestion endpoint to generate contextual
 * continuation suggestions for detailed lead descriptions.
 *
 * @param currentText The current text entered by the user (min 20 chars)
 * @param briefDescription The brief description/title of the lead
 * @param clientName The name of the client
 * @param leadManagerName The name of the lead manager
 */
public record DescriptionSuggestionRequest(
    String currentText,
    String briefDescription,
    String clientName,
    String leadManagerName
) {}
