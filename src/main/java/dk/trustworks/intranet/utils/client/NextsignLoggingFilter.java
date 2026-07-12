package dk.trustworks.intranet.utils.client;

import jakarta.ws.rs.client.ClientRequestContext;
import jakarta.ws.rs.client.ClientRequestFilter;
import jakarta.ws.rs.client.ClientResponseContext;
import jakarta.ws.rs.client.ClientResponseFilter;
import lombok.extern.jbosslog.JBossLog;

import java.io.IOException;

/**
 * Logging filter for Nextsign REST client.
 * Logs request method/URI and response status without inspecting headers or bodies.
 */
@JBossLog
public class NextsignLoggingFilter implements ClientRequestFilter, ClientResponseFilter {

    @Override
    public void filter(ClientRequestContext requestContext) throws IOException {
        log.infof("[NEXTSIGN-REQUEST] %s %s",
            requestContext.getMethod(),
            requestContext.getUri());
    }

    @Override
    public void filter(ClientRequestContext requestContext, ClientResponseContext responseContext) throws IOException {
        log.infof("[NEXTSIGN-RESPONSE] %d", responseContext.getStatus());
    }
}
