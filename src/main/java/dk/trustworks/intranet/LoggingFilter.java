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

    private static final int MAX_BODY_LOG_LENGTH = 200;

    @Override
    public void filter(ContainerRequestContext requestContext) throws IOException {
        String path = requestContext.getUriInfo().getPath();

        // Skip logging for noisy polling endpoints
        if (path.contains("/notifications")) {
            return;
        }

        MediaType mediaType = requestContext.getMediaType();
        boolean isJson = mediaType != null && mediaType.toString().startsWith(MediaType.APPLICATION_JSON);

        if (requestContext.getMethod().equals(HttpMethod.POST) && isJson) {
            logRequestBody(requestContext);
        }
    }

    private void logRequestBody(ContainerRequestContext requestContext) throws IOException {
        InputStream originalStream = requestContext.getEntityStream();
        byte[] requestEntity = originalStream.readAllBytes();

        String path = requestContext.getUriInfo().getPath();
        long contentLength = requestEntity.length;

        // Single condensed request line
        log.info(path);

        // Log only meaningful headers: Content-Type and X-Requested-By
        String contentType = requestContext.getHeaderString("Content-Type");
        String requestedBy = requestContext.getHeaderString("X-Requested-By");
        if (contentType != null) log.info("Content-Type: " + contentType);
        if (requestedBy != null) log.info("X-Requested-By: " + requestedBy);

        // Log request body — skip base64 payloads (screenshots), truncate others
        String body = new String(requestEntity, StandardCharsets.UTF_8);
        if (body.contains("base64") || body.contains("iVBOR")) {
            log.info("Request body: [base64 payload, " + contentLength + " bytes, skipped]");
        } else if (body.length() > MAX_BODY_LOG_LENGTH) {
            log.info("Request body: " + body.substring(0, MAX_BODY_LOG_LENGTH) + "... [truncated, " + contentLength + " bytes total]");
        } else {
            log.info("Request body: " + body);
        }

        // Restore the original input stream so downstream can read it
        requestContext.setEntityStream(new ByteArrayInputStream(requestEntity));
    }
}
