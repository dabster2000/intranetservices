package dk.trustworks.intranet.exceptions;

/**
 * Standardized error response body for REST API errors.
 * All exception mappers should return this shape as JSON.
 */
public record ErrorResponse(String error, int status) {
}
