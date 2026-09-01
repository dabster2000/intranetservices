package dk.trustworks.intranet.graph;

import jakarta.ws.rs.core.MultivaluedMap;
import jakarta.ws.rs.core.Response;
import lombok.extern.jbosslog.JBossLog;
import org.eclipse.microprofile.rest.client.ext.ResponseExceptionMapper;

import java.io.InputStream;
import java.nio.charset.StandardCharsets;

/**
 * Custom exception mapper for Microsoft Graph API REST client.
 * Captures error response details for debugging and proper error handling.
 */
@JBossLog
public class GraphResponseExceptionMapper implements ResponseExceptionMapper<GraphResponseExceptionMapper.GraphApiException> {

    @Override
    public GraphApiException toThrowable(Response response) {
        int status = response.getStatus();
        String statusInfo = response.getStatusInfo().getReasonPhrase();
        // Headers must be read BEFORE the body: readResponseBody drains the
        // entity stream, and a drained response is not a safe place to go
        // looking for anything else.
        Integer retryAfter = readRetryAfterSeconds(response);
        String responseBody = readResponseBody(response);

        log.errorf("Graph API error - Status: %d %s, Body: %s", status, statusInfo, responseBody);

        return new GraphApiException(
            formatErrorMessage(status, statusInfo, responseBody),
            status,
            retryAfter,
            extractRequestId(responseBody)
        );
    }

    /**
     * Graph's correlation id ({@code innerError.request-id}) — the handle
     * Microsoft support asks for, and the one thing that lets an operator
     * tie our failure to Graph's own logs. The 2026-08-24 candidate-invite
     * 504 carried an EMPTY {@code message}, so without this id the alert
     * would have said nothing actionable at all.
     */
    public static String extractRequestId(String responseBody) {
        if (responseBody == null) {
            return null;
        }
        java.util.regex.Matcher matcher = java.util.regex.Pattern
                .compile("\"request-id\"\\s*:\\s*\"([^\"]+)\"")
                .matcher(responseBody);
        return matcher.find() ? matcher.group(1) : null;
    }

    /**
     * Graph's throttling back-pressure, in seconds — the only part of a 429
     * that tells us how long to wait (production 2026-08-15: the
     * {@code MailboxConcurrency} burst was retried instantly because this
     * header was discarded here).
     * <p>
     * Two spellings are read: the standard {@code Retry-After} (delta
     * seconds) and Microsoft's {@code x-ms-retry-after-ms} (milliseconds,
     * rounded UP so we never come back early). The HTTP-date form of
     * {@code Retry-After} is deliberately NOT parsed — Graph does not send
     * it for throttling, and guessing a clock offset is worse than falling
     * back to the caller's own default. Never throws: a header we cannot
     * read is simply absent.
     */
    private Integer readRetryAfterSeconds(Response response) {
        try {
            String retryAfter = response.getHeaderString("Retry-After");
            if (retryAfter != null && !retryAfter.isBlank()) {
                int seconds = Integer.parseInt(retryAfter.trim());
                if (seconds >= 0) {
                    return seconds;
                }
            }
        } catch (NumberFormatException e) {
            // HTTP-date or junk — fall through to the millisecond header.
        }
        try {
            String retryAfterMs = response.getHeaderString("x-ms-retry-after-ms");
            if (retryAfterMs != null && !retryAfterMs.isBlank()) {
                long millis = Long.parseLong(retryAfterMs.trim());
                if (millis >= 0) {
                    return (int) ((millis + 999) / 1000);
                }
            }
        } catch (NumberFormatException e) {
            // Unreadable — absent.
        }
        return null;
    }

    @Override
    public boolean handles(int status, MultivaluedMap<String, Object> headers) {
        // Handle all error responses (4xx and 5xx)
        return status >= 400;
    }

    private String readResponseBody(Response response) {
        try {
            if (response.hasEntity()) {
                Object entity = response.getEntity();

                if (entity instanceof InputStream inputStream) {
                    return new String(inputStream.readAllBytes(), StandardCharsets.UTF_8);
                }

                if (entity instanceof String s) {
                    return s;
                }

                return response.readEntity(String.class);
            }
            return "<no response body>";
        } catch (Exception e) {
            log.warnf(e, "Failed to read Graph API error response body");
            return "<failed to read body: " + e.getMessage() + ">";
        }
    }

    private String formatErrorMessage(int status, String statusInfo, String responseBody) {
        // Try to extract a meaningful message from the Graph API error response
        if (responseBody != null && responseBody.contains("\"message\"")) {
            // Simple extraction - could be enhanced with proper JSON parsing
            int start = responseBody.indexOf("\"message\"");
            if (start >= 0) {
                int colonPos = responseBody.indexOf(":", start);
                int endQuote = responseBody.indexOf("\"", colonPos + 2);
                int nextQuote = responseBody.indexOf("\"", endQuote + 1);
                // A BLANK extracted message falls through to the full-body
                // format: Graph's gateway errors (the 2026-08-24 504) carry
                // "message":"" and the old path rendered them as
                // "Graph API error 504: ," — an error that explains nothing.
                if (colonPos > 0 && endQuote > colonPos && nextQuote > endQuote + 1) {
                    String message = responseBody.substring(endQuote + 1, nextQuote);
                    return String.format("Graph API error %d: %s", status, message);
                }
            }
        }
        return String.format("Graph API error %d %s: %s", status, statusInfo, responseBody);
    }

    /**
     * Custom exception for Microsoft Graph API errors.
     */
    public static class GraphApiException extends RuntimeException {
        private final int statusCode;
        private final Integer retryAfterSeconds;
        private final String requestId;

        public GraphApiException(String message, int statusCode) {
            this(message, statusCode, null);
        }

        public GraphApiException(String message, int statusCode, Integer retryAfterSeconds) {
            this(message, statusCode, retryAfterSeconds, null);
        }

        public GraphApiException(String message, int statusCode, Integer retryAfterSeconds,
                                   String requestId) {
            super(message);
            this.statusCode = statusCode;
            this.retryAfterSeconds = retryAfterSeconds;
            this.requestId = requestId;
        }

        /**
         * Graph's {@code innerError.request-id} for this failure, or null
         * when the body carried none. The correlation handle for operator
         * alerts and Microsoft support tickets.
         */
        public String getRequestId() {
            return requestId;
        }

        public int getStatusCode() {
            return statusCode;
        }

        /**
         * The {@code Retry-After} back-pressure Graph asked for, in seconds,
         * or null when it sent none (or sent it in a form we do not parse).
         * Callers must apply their own cap — Graph can ask for an hour, and
         * no request-scoped caller may wait that long.
         */
        public Integer getRetryAfterSeconds() {
            return retryAfterSeconds;
        }

        /**
         * Graph is asking us to slow down (HTTP 429). Distinct from every
         * other 4xx in one decisive way: it says nothing about the resource
         * we asked for, only about our own call rate — so the remedy is to
         * wait, never to try a different address.
         */
        public boolean isThrottled() {
            return statusCode == 429;
        }

        /**
         * Determines if the error indicates the resource was not found.
         */
        public boolean isNotFound() {
            return statusCode == 404;
        }

        /**
         * Determines if the error indicates an authorization issue.
         */
        public boolean isUnauthorized() {
            return statusCode == 401 || statusCode == 403;
        }

        /**
         * Determines if the error is a client error (4xx).
         */
        public boolean isClientError() {
            return statusCode >= 400 && statusCode < 500;
        }

        /**
         * Determines if the error is a server error (5xx).
         */
        public boolean isServerError() {
            return statusCode >= 500;
        }
    }
}
