package dk.trustworks.intranet.sharepoint.client;

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
public class GraphResponseExceptionMapper implements ResponseExceptionMapper<GraphResponseExceptionMapper.SharePointException> {

    @Override
    public SharePointException toThrowable(Response response) {
        int status = response.getStatus();
        String statusInfo = response.getStatusInfo().getReasonPhrase();
        String responseBody = readResponseBody(response);

        log.errorf("Graph API error - Status: %d %s, Body: %s", status, statusInfo, responseBody);

        return new SharePointException(
            formatErrorMessage(status, statusInfo, responseBody),
            status
        );
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
                if (colonPos > 0 && endQuote > colonPos && nextQuote > endQuote) {
                    String message = responseBody.substring(endQuote + 1, nextQuote);
                    return String.format("Graph API error %d: %s", status, message);
                }
            }
        }
        return String.format("Graph API error %d %s: %s", status, statusInfo, responseBody);
    }

    /**
     * Custom exception for SharePoint/Graph API errors.
     */
    public static class SharePointException extends RuntimeException {
        private final int statusCode;

        public SharePointException(String message, int statusCode) {
            super(message);
            this.statusCode = statusCode;
        }

        public int getStatusCode() {
            return statusCode;
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
