package dk.trustworks.intranet.utils.client;

import jakarta.ws.rs.core.MultivaluedMap;
import jakarta.ws.rs.core.Response;
import lombok.extern.jbosslog.JBossLog;
import org.eclipse.microprofile.rest.client.ext.ResponseExceptionMapper;

/**
 * Custom exception mapper for Nextsign REST client.
 * Captures only the HTTP status. Response bodies can contain personal data,
 * session tokens, and signed URLs and must not enter logs or exception messages.
 */
@JBossLog
public class NextsignResponseExceptionMapper implements ResponseExceptionMapper<NextsignResponseExceptionMapper.NextsignApiException> {

    @Override
    public NextsignApiException toThrowable(Response response) {
        int status = response.getStatus();
        String statusInfo = response.getStatusInfo().getReasonPhrase();

        log.errorf("Nextsign API error - Status: %d %s", status, statusInfo);

        return new NextsignApiException(status, statusInfo);
    }

    @Override
    public boolean handles(int status, MultivaluedMap<String, Object> headers) {
        // Handle all error responses (4xx and 5xx)
        return status >= 400;
    }

    /**
     * Custom exception containing safe Nextsign HTTP error details.
     */
    public static class NextsignApiException extends RuntimeException {
        private final int statusCode;
        private final String statusInfo;

        public NextsignApiException(int statusCode, String statusInfo) {
            super(String.format("Nextsign API error %d %s", statusCode, statusInfo));
            this.statusCode = statusCode;
            this.statusInfo = statusInfo;
        }

        public int getStatusCode() {
            return statusCode;
        }

        public String getStatusInfo() {
            return statusInfo;
        }
    }
}
