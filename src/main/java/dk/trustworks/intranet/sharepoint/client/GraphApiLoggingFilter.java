package dk.trustworks.intranet.sharepoint.client;

import jakarta.ws.rs.client.ClientRequestContext;
import jakarta.ws.rs.client.ClientRequestFilter;
import lombok.extern.jbosslog.JBossLog;

import java.io.IOException;

/**
 * Logging filter for Microsoft Graph API REST client.
 * Logs outgoing requests for debugging purposes.
 */
@JBossLog
public class GraphApiLoggingFilter implements ClientRequestFilter {

    @Override
    public void filter(ClientRequestContext requestContext) throws IOException {
        // Log request details (mask authorization header for security)
        String authHeader = requestContext.getHeaderString("Authorization");
        String maskedAuth = authHeader != null ? "Bearer ***" : "none";

        log.debugf("Graph API request: %s %s [Auth: %s]",
            requestContext.getMethod(),
            requestContext.getUri(),
            maskedAuth
        );
    }
}
