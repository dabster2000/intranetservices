package dk.trustworks.intranet.security;

import org.eclipse.microprofile.jwt.JsonWebToken;
import org.jboss.logging.MDC;
import lombok.extern.jbosslog.JBossLog;

import jakarta.inject.Inject;
import jakarta.ws.rs.container.ContainerRequestContext;
import jakarta.ws.rs.container.ContainerRequestFilter;
import jakarta.ws.rs.container.ContainerResponseContext;
import jakarta.ws.rs.container.ContainerResponseFilter;
import jakarta.ws.rs.core.Context;
import jakarta.ws.rs.core.HttpHeaders;
import jakarta.ws.rs.core.UriInfo;
import jakarta.ws.rs.ext.Provider;
import java.io.IOException;

@JBossLog
@Provider
public class HeaderInterceptor implements ContainerRequestFilter, ContainerResponseFilter {

    @Context
    HttpHeaders headers;

    @Context
    UriInfo uriInfo;

    @Inject
    JsonWebToken jwt;

    @Inject
    RequestHeaderHolder requestHeaderHolder;

    @Override
    public void filter(ContainerRequestContext context) throws IOException {
        String requestedBy = context.getHeaders().getFirst("X-Requested-By");
        if (requestedBy == null || requestedBy.isEmpty()) {
            requestedBy = jwt.getClaim("preferred_username");
            if (requestedBy != null) log.debugf("User identifier resolved from JWT: %s", requestedBy);
        }
        if (requestedBy == null || requestedBy.isEmpty()) {
            requestedBy = uriInfo.getQueryParameters().getFirst("username");
            if (requestedBy != null) log.debugf("User identifier resolved from query param: %s", requestedBy);
        }
        if (requestedBy == null || requestedBy.isEmpty()) {
            requestedBy = "anonymous";
            log.debug("User identifier not found in request; defaulting to anonymous");
        }

        requestHeaderHolder.setUserUuid(requestedBy);
        MDC.put("userUuid", requestedBy);
        log.debugf("Request userUuid set to %s", requestedBy);
    }

    @Override
    public void filter(ContainerRequestContext requestContext, ContainerResponseContext responseContext) throws IOException {
        MDC.remove("userUuid");
    }
}
