package dk.trustworks.intranet.apigateway.logging;

import io.vertx.core.http.HttpServerRequest;
import lombok.extern.jbosslog.JBossLog;

import javax.ws.rs.container.ContainerRequestContext;
import javax.ws.rs.container.ContainerRequestFilter;
import javax.ws.rs.core.Context;
import javax.ws.rs.core.MultivaluedMap;
import javax.ws.rs.core.UriInfo;
import javax.ws.rs.ext.Provider;
import java.util.List;
import java.util.stream.Collectors;

@JBossLog
@Provider
public class LoggingFilter implements ContainerRequestFilter {

    @Context
    UriInfo info;

    @Context
    HttpServerRequest request;

    @Override
    public void filter(ContainerRequestContext context) {
        final String method = context.getMethod();
        final String path = info.getPath();
        final String address = request.remoteAddress().toString();
        MultivaluedMap<String, String> queryParameters = info.getQueryParameters();
        List<String> strings = queryParameters.keySet().stream().map(s -> s + "=" + queryParameters.get(s)).collect(Collectors.toList());

        //log.infof("Request %s %s from IP %s with params [%s]", method, path, address, String.join(",", strings));
    }
}