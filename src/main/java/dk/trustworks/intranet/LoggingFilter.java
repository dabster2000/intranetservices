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
import java.util.List;
import java.util.regex.Pattern;

@JBossLog
@Provider
public class LoggingFilter implements ContainerRequestFilter {

    private static final int MAX_BODY_LOG_LENGTH = 200;

    static final String REDACTED_MARKER = "\"***REDACTED***\"";

    /**
     * Endpoints whose request body carries a live credential and must therefore
     * never reach a log line — at any level, redacted or not. This is the hard
     * guarantee; {@link #redactSecrets(String)} below is the safety net for
     * everything else.
     * <p>
     * Matched against the JAX-RS path with any leading slash removed, so both
     * {@code auth/token} and {@code /auth/token} are covered regardless of which
     * shape the runtime hands us.
     */
    private static final List<String> CREDENTIAL_ENDPOINTS = List.of(
            "auth/token"
    );

    /**
     * JSON keys whose values are secrets. Any body that is not covered by
     * {@link #CREDENTIAL_ENDPOINTS} is still scrubbed of these before it is
     * formatted for a log line, so the next endpoint that starts carrying a
     * secret is redacted by default rather than by someone remembering to add it
     * here.
     * <p>
     * This mirrors the redaction convention already used elsewhere in the
     * codebase: {@code User.password} / {@code User.cpr} are {@code @JsonIgnore}
     * and {@code UserScopeResponseFilter} strips sensitive fields on responses.
     */
    private static final Pattern SECRET_FIELD = Pattern.compile(
            "(\"(?:client_secret|clientSecret|client-secret|password|passwd|pwd|secret|"
                    + "token|access_token|accessToken|refresh_token|refreshToken|id_token|idToken|"
                    + "authorization|api_key|apiKey|apikey|private_key|privateKey|cpr)\"\\s*:\\s*)"
                    + "(\"(?:\\\\.|[^\"\\\\])*\"|-?[0-9][0-9.eE+-]*|true|false|null)",
            Pattern.CASE_INSENSITIVE);

    @Override
    public void filter(ContainerRequestContext requestContext) throws IOException {
        String path = requestContext.getUriInfo().getPath();

        // Skip logging for noisy polling endpoints
        if (path.contains("/notifications") || path.startsWith("individual-bonuses")) {
            return;
        }

        // Never body-log inbound Slack dispatch: the envelope carries Slack
        // user/payload ids today and P14+ handler payloads may carry personal
        // data (modal values, referral text) — the P13 no-PII-in-logs rule.
        if (path.contains("/recruitment/slack/inbound")) {
            return;
        }

        // Never body-log candidate emails (P15): send/render/approve bodies
        // carry rendered candidate correspondence — the same no-PII rule.
        if (path.contains("/emails/") && path.contains("/recruitment/")) {
            return;
        }

        MediaType mediaType = requestContext.getMediaType();
        boolean isJson = mediaType != null && mediaType.toString().startsWith(MediaType.APPLICATION_JSON);

        if (requestContext.getMethod().equals(HttpMethod.POST) && isJson) {
            // Credential endpoints: record that the call happened, never what was in
            // it. The entity stream is not touched at all, so this filter never holds
            // a buffered copy of the secret.
            if (isCredentialEndpoint(path)) {
                log.info(sanitiseForLog(path) + " [request body withheld: credential endpoint]");
                return;
            }
            logRequestBody(requestContext);
        }
    }

    /**
     * True when the request body for this path must never be logged because it
     * carries a credential in cleartext.
     */
    static boolean isCredentialEndpoint(String path) {
        if (path == null) return false;
        String normalised = path.startsWith("/") ? path.substring(1) : path;
        for (String endpoint : CREDENTIAL_ENDPOINTS) {
            if (normalised.equals(endpoint)
                    || normalised.startsWith(endpoint + "/")
                    || normalised.contains("/" + endpoint)) {
                return true;
            }
        }
        return false;
    }

    /**
     * Strips line breaks from a caller-controlled value before it is logged.
     * {@code UriInfo.getPath()} is URL-decoded, so a request to {@code /a%0AFAKE}
     * would otherwise let the caller forge additional log lines in CloudWatch.
     */
    static String sanitiseForLog(String value) {
        if (value == null) return null;
        return value.replace('\n', '_').replace('\r', '_');
    }

    /**
     * Replaces the value of every known secret-bearing JSON key with a redaction
     * marker. Best effort by design — it is the net under the explicit
     * {@link #CREDENTIAL_ENDPOINTS} exclusion, not a replacement for it.
     */
    static String redactSecrets(String body) {
        if (body == null || body.isEmpty()) return body;
        return SECRET_FIELD.matcher(body).replaceAll("$1" + REDACTED_MARKER);
    }

    /**
     * Builds the loggable representation of a request body: base64 payloads are
     * skipped, secrets are redacted, and only then is the result shortened —
     * redacting first means a partial log line can never leave a usable prefix of
     * a secret behind.
     */
    static String formatBodyForLog(byte[] requestEntity) {
        long contentLength = requestEntity.length;
        String body = new String(requestEntity, StandardCharsets.UTF_8);

        if (body.contains("base64") || body.contains("iVBOR")) {
            return "[base64 payload, " + contentLength + " bytes, skipped]";
        }

        String redacted = redactSecrets(body);
        if (redacted.length() > MAX_BODY_LOG_LENGTH) {
            return redacted.substring(0, MAX_BODY_LOG_LENGTH)
                    + "... [shortened, " + contentLength + " bytes total]";
        }
        return redacted;
    }

    private void logRequestBody(ContainerRequestContext requestContext) throws IOException {
        String path = requestContext.getUriInfo().getPath();

        // Single condensed request line
        log.info(sanitiseForLog(path));

        // Log only meaningful headers: Content-Type and X-Requested-By
        String contentType = requestContext.getHeaderString("Content-Type");
        String requestedBy = requestContext.getHeaderString("X-Requested-By");
        if (contentType != null) log.info("Content-Type: " + sanitiseForLog(contentType));
        if (requestedBy != null) log.info("X-Requested-By: " + sanitiseForLog(requestedBy));

        // Body logging is DEBUG: it is a debugging aid, and at INFO it was
        // permanently on in production. Raise the category level when needed.
        if (!log.isDebugEnabled()) {
            return;
        }

        InputStream originalStream = requestContext.getEntityStream();
        byte[] requestEntity = originalStream.readAllBytes();

        log.debug("Request body: " + formatBodyForLog(requestEntity));

        // Restore the original input stream so downstream can read it
        requestContext.setEntityStream(new ByteArrayInputStream(requestEntity));
    }
}
