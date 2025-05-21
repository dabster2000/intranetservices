package dk.trustworks.intranet;


import jakarta.ws.rs.HttpMethod;
import jakarta.ws.rs.container.ContainerRequestContext;
import jakarta.ws.rs.container.ContainerRequestFilter;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.ext.Provider;
import lombok.extern.jbosslog.JBossLog;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;

@JBossLog
@Provider
public class LoggingFilter implements ContainerRequestFilter {

    @Override
    public void filter(ContainerRequestContext requestContext) throws IOException {

        if (requestContext.getMethod().equals(HttpMethod.POST) && requestContext.getMediaType() != null && MediaType.APPLICATION_JSON.equals(requestContext.getMediaType().toString())) {
            logRequestBody(requestContext);
        }
    }

    private void logRequestBody(ContainerRequestContext requestContext) throws IOException {
        InputStream originalStream = requestContext.getEntityStream();
        byte[] requestEntity = originalStream.readAllBytes();
        requestContext.getHeaders().forEach((k, v) -> log.info(k + ": " + v));
        requestContext.getUriInfo().getPathParameters().forEach((k, v) -> log.info(k + ": " + v));
        requestContext.getUriInfo().getQueryParameters().forEach((k, v) -> log.info(k + ": " + v));
        log.info(requestContext.getUriInfo().getPath());
        if (requestEntity.length > 0) {
            // Log the request body
            String body = new String(requestEntity, StandardCharsets.UTF_8);
            //log.info("Request body: " + body);

            // Restore the original input stream
            requestContext.setEntityStream(new ByteArrayInputStream(requestEntity));
        }

    }


}