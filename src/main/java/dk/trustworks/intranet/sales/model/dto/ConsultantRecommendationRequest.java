package dk.trustworks.intranet.sales.model.dto;

/**
 * Optional request body for the consultant recommendation endpoint.
 * The contextHint provides additional free-text context from the account manager
 * to guide the AI recommendation (e.g. "needs fluent German", "prefers remote").
 *
 * @param contextHint Optional free-text hint from the caller (trimmed to 200 chars, HTML stripped).
 */
public record ConsultantRecommendationRequest(String contextHint) {}
