package dk.trustworks.intranet.logging;

import dk.trustworks.intranet.security.RequestHeaderHolder;
import jakarta.inject.Inject;
import jakarta.ws.rs.container.ContainerRequestContext;
import jakarta.ws.rs.container.ContainerRequestFilter;
import jakarta.ws.rs.container.ContainerResponseContext;
import jakarta.ws.rs.container.ContainerResponseFilter;
import jakarta.ws.rs.ext.Provider;
import lombok.extern.jbosslog.JBossLog;

import java.io.IOException;

@JBossLog
@Provider
public class ApiUsageLoggingFilter implements ContainerRequestFilter, ContainerResponseFilter {

    @Inject
    RequestHeaderHolder requestHeaderHolder;

    @Inject
    ApiUsageLogService logService;

    @Override
    public void filter(ContainerRequestContext requestContext) throws IOException {
        requestContext.setProperty("apiUsageStartTime", System.currentTimeMillis());
        log.debugf("Started request %s %s", requestContext.getMethod(), requestContext.getUriInfo().getPath());
    }

    @Override
    public void filter(ContainerRequestContext requestContext, ContainerResponseContext responseContext) throws IOException {
        long start = (long) requestContext.getProperty("apiUsageStartTime");
        long duration = System.currentTimeMillis() - start;
        String path = requestContext.getUriInfo().getPath();
        String method = requestContext.getMethod();
        String referer = requestContext.getHeaderString("Referer");
        if(referer == null) {
            referer = requestContext.getHeaderString("X-View-Id");
        }
        log.infof("API request - user=%s method=%s path=%s referer=%s duration=%dms",
                requestHeaderHolder.getUsername(), method, path, referer, duration);
        logService.record(requestHeaderHolder.getUsername(), method, path, referer, duration);
    }
}
