package dk.trustworks.intranet.utils.client;

import jakarta.ws.rs.core.MultivaluedMap;
import jakarta.ws.rs.core.Response;
import lombok.extern.jbosslog.JBossLog;
import org.eclipse.microprofile.rest.client.ext.ResponseExceptionMapper;

import java.io.InputStream;
import java.nio.charset.StandardCharsets;

/**
 * Custom exception mapper for Nextsign REST client.
 * Captures the actual error response body for debugging purposes.
 */
@JBossLog
public class NextsignResponseExceptionMapper implements ResponseExceptionMapper<NextsignResponseExceptionMapper.NextsignApiException> {

    @Override
    public NextsignApiException toThrowable(Response response) {
        int status = response.getStatus();
        String statusInfo = response.getStatusInfo().getReasonPhrase();
        String responseBody = readResponseBody(response);

        log.errorf("Nextsign API error - Status: %d %s, Body: %s", status, statusInfo, responseBody);

        return new NextsignApiException(status, statusInfo, responseBody);
    }

    @Override
    public boolean handles(int status, MultivaluedMap<String, Object> headers) {
        // Handle all error responses (4xx and 5xx)
        return status >= 400;
    }

    private String readResponseBody(Response response) {
        try {
            // Try to read entity as String
            if (response.hasEntity()) {
                Object entity = response.getEntity();

                if (entity instanceof InputStream inputStream) {
                    return new String(inputStream.readAllBytes(), StandardCharsets.UTF_8);
                }

                if (entity instanceof String s) {
                    return s;
                }

                // Try reading as String directly
                return response.readEntity(String.class);
            }
            return "<no response body>";
        } catch (Exception e) {
            log.warnf(e, "Failed to read Nextsign error response body");
            return "<failed to read body: " + e.getMessage() + ">";
        }
    }

    /**
     * Custom exception containing Nextsign API error details.
     */
    public static class NextsignApiException extends RuntimeException {
        private final int statusCode;
        private final String statusInfo;
        private final String responseBody;

        public NextsignApiException(int statusCode, String statusInfo, String responseBody) {
            super(String.format("Nextsign API error %d %s: %s", statusCode, statusInfo, responseBody));
            this.statusCode = statusCode;
            this.statusInfo = statusInfo;
            this.responseBody = responseBody;
        }

        public int getStatusCode() {
            return statusCode;
        }

        public String getStatusInfo() {
            return statusInfo;
        }

        public String getResponseBody() {
            return responseBody;
        }
    }
}
