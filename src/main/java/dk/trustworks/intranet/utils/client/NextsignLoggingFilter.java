package dk.trustworks.intranet.utils.client;

import jakarta.ws.rs.client.ClientRequestContext;
import jakarta.ws.rs.client.ClientRequestFilter;
import jakarta.ws.rs.client.ClientResponseContext;
import jakarta.ws.rs.client.ClientResponseFilter;
import lombok.extern.jbosslog.JBossLog;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;

/**
 * Logging filter for Nextsign REST client.
 * Logs request and response details for debugging.
 */
@JBossLog
public class NextsignLoggingFilter implements ClientRequestFilter, ClientResponseFilter {

    @Override
    public void filter(ClientRequestContext requestContext) throws IOException {
        log.infof("[NEXTSIGN-REQUEST] %s %s",
            requestContext.getMethod(),
            requestContext.getUri());
        log.debugf("[NEXTSIGN-REQUEST] Headers: %s", requestContext.getStringHeaders());
    }

    @Override
    public void filter(ClientRequestContext requestContext, ClientResponseContext responseContext) throws IOException {
        int status = responseContext.getStatus();
        String statusInfo = responseContext.getStatusInfo().getReasonPhrase();

        log.infof("[NEXTSIGN-RESPONSE] %d %s", status, statusInfo);
        log.debugf("[NEXTSIGN-RESPONSE] Headers: %s", responseContext.getHeaders());

        // Read and log response body (for debugging)
        if (responseContext.hasEntity()) {
            InputStream entityStream = responseContext.getEntityStream();
            byte[] bytes = entityStream.readAllBytes();
            String responseBody = new String(bytes, StandardCharsets.UTF_8);

            // Log response body (truncate if too long)
            if (responseBody.length() > 2000) {
                log.infof("[NEXTSIGN-RESPONSE] Body (truncated): %s...", responseBody.substring(0, 2000));
            } else {
                log.infof("[NEXTSIGN-RESPONSE] Body: %s", responseBody);
            }

            // Reset stream so it can be read again for deserialization
            responseContext.setEntityStream(new ByteArrayInputStream(bytes));
        } else {
            log.info("[NEXTSIGN-RESPONSE] No body");
        }
    }
}
