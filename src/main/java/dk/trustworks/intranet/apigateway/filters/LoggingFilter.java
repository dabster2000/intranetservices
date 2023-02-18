package dk.trustworks.intranet.apigateway.filters;

import io.vertx.core.http.HttpServerRequest;

import javax.ws.rs.container.ContainerRequestContext;
import javax.ws.rs.container.ContainerRequestFilter;
import javax.ws.rs.container.ContainerResponseContext;
import javax.ws.rs.container.ContainerResponseFilter;
import javax.ws.rs.core.Context;
import javax.ws.rs.core.UriInfo;
import javax.ws.rs.ext.Provider;
import java.io.IOException;

@Provider
public class LoggingFilter implements ContainerResponseFilter, ContainerRequestFilter {

    @Context
    UriInfo info;

    @Context
    HttpServerRequest request;

    @Override
    public void filter(ContainerRequestContext context) {
/*
        Span span = GlobalTracer.get().buildSpan("RestService").start();
        try (Scope ignored = GlobalTracer.get().scopeManager().activate(span)) {
            final SecurityContext securityContext = context.getSecurityContext();
            final Principal principal = securityContext!=null?securityContext.getUserPrincipal():null;
            final String username = principal!=null?principal.getName():"no user";
            final String auth = context.getHeaderString("Authorization");
            final String method = context.getMethod();
            final String path = info.getPath();
            final String address = request.remoteAddress().toString();
            //span.log(ImmutableMap.of("path", path, "address", address, "method", method, "username", username, "auth", auth));
        } catch (Exception e) {
            span.log(e.toString());
        } finally {
            span.finish();
        }
*/

    }

    @Override
    public void filter(ContainerRequestContext requestContext, ContainerResponseContext responseContext) throws IOException {

    }
}
