package dk.trustworks.intranet.aggregates.invoice.network;

import jakarta.ws.rs.client.ClientRequestContext;
import jakarta.ws.rs.client.ClientRequestFilter;
import jakarta.ws.rs.core.MultivaluedMap;

import java.io.IOException;

public class InvoiceDynamicHeaderFilter implements ClientRequestFilter {
    private final String jwtToken;

    public InvoiceDynamicHeaderFilter(String jwtToken) {
        this.jwtToken = jwtToken;
    }

    @Override
    public void filter(ClientRequestContext requestContext) throws IOException {
        MultivaluedMap<String, Object> headers = requestContext.getHeaders();
        headers.add("Authorization", "Bearer " + jwtToken);
    }
}
