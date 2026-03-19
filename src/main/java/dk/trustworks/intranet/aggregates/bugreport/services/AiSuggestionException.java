package dk.trustworks.intranet.aggregates.bugreport.services;

/**
 * Thrown when the AI suggestion service fails (OpenAI unavailable, empty response, parse error).
 * Mapped to HTTP 502 Bad Gateway by the resource layer.
 */
public class AiSuggestionException extends RuntimeException {

    public AiSuggestionException(String message) {
        super(message);
    }

    public AiSuggestionException(String message, Throwable cause) {
        super(message, cause);
    }
}
