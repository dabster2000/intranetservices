package dk.trustworks.intranet.security;

import org.eclipse.microprofile.jwt.JsonWebToken;

import jakarta.inject.Inject;
import jakarta.ws.rs.container.ContainerRequestContext;
import jakarta.ws.rs.container.ContainerRequestFilter;
import jakarta.ws.rs.core.Context;
import jakarta.ws.rs.core.HttpHeaders;
import jakarta.ws.rs.ext.Provider;
import java.io.IOException;

@Provider
public class HeaderInterceptor implements ContainerRequestFilter {

    @Context
    HttpHeaders headers;

    @Inject
    JsonWebToken jwt;

    @Inject
    RequestHeaderHolder requestHeaderHolder;

    @Override
    public void filter(ContainerRequestContext context) throws IOException {
        String requestedBy = context.getHeaders().getFirst("X-Requested-By");

        if (requestedBy == null || requestedBy.isEmpty()) {
            requestedBy = jwt.getClaim("preferred_username");
        }

        requestHeaderHolder.setUsername(requestedBy);
    }
}
