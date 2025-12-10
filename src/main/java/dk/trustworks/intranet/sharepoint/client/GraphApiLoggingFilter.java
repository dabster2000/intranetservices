package dk.trustworks.intranet.sharepoint.client;

import jakarta.ws.rs.client.ClientRequestContext;
import jakarta.ws.rs.client.ClientRequestFilter;
import lombok.extern.jbosslog.JBossLog;

import java.io.IOException;
import java.net.URI;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Logging filter for Microsoft Graph API REST client.
 * Also fixes double-encoding issues that can occur with path parameters.
 *
 * <p>Quarkus REST client may double-encode path parameters even when marked with @Encoded.
 * This filter detects patterns like %2520 (double-encoded space) and fixes them.
 *
 * @see <a href="https://github.com/quarkusio/quarkus/issues/38123">Quarkus Issue #38123</a>
 */
@JBossLog
public class GraphApiLoggingFilter implements ClientRequestFilter {

    /**
     * Pattern to detect double-encoded percent signs.
     * Matches %25 followed by two hex digits (e.g., %2520 = double-encoded space).
     */
    private static final Pattern DOUBLE_ENCODED_PATTERN = Pattern.compile("%25([0-9A-Fa-f]{2})");

    @Override
    public void filter(ClientRequestContext requestContext) throws IOException {
        URI originalUri = requestContext.getUri();
        String originalUriString = originalUri.toString();

        // Fix double-encoding if detected
        String fixedUriString = fixDoubleEncoding(originalUriString);
        if (!fixedUriString.equals(originalUriString)) {
            URI fixedUri = URI.create(fixedUriString);
            requestContext.setUri(fixedUri);
            log.warnf("Fixed double-encoded URL: %s -> %s", originalUriString, fixedUriString);
        }

        // Log request details (mask authorization header for security)
        String authHeader = requestContext.getHeaderString("Authorization");
        String maskedAuth = authHeader != null ? "Bearer ***" : "none";

        log.infof("Graph API request: %s %s [Auth: %s]",
            requestContext.getMethod(),
            requestContext.getUri(),
            maskedAuth
        );
    }

    /**
     * Fixes double-encoded URLs by replacing %25XX with %XX.
     * For example: %2520 (double-encoded space) -> %20 (single-encoded space)
     *
     * @param url the URL string to fix
     * @return the fixed URL string
     */
    private String fixDoubleEncoding(String url) {
        if (url == null || !url.contains("%25")) {
            return url;
        }

        Matcher matcher = DOUBLE_ENCODED_PATTERN.matcher(url);
        StringBuffer result = new StringBuffer();

        while (matcher.find()) {
            // Replace %25XX with %XX
            String replacement = "%" + matcher.group(1);
            matcher.appendReplacement(result, replacement);
        }
        matcher.appendTail(result);

        return result.toString();
    }
}
