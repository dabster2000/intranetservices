package dk.trustworks.intranet.security;

import org.eclipse.microprofile.jwt.JsonWebToken;

import javax.inject.Inject;
import javax.ws.rs.container.ContainerRequestContext;
import javax.ws.rs.container.ContainerRequestFilter;
import javax.ws.rs.core.Context;
import javax.ws.rs.core.HttpHeaders;
import javax.ws.rs.ext.Provider;
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
