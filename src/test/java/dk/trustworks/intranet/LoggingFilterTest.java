package dk.trustworks.intranet;

import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.UriInfo;
import jakarta.ws.rs.container.ContainerRequestContext;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.io.ByteArrayInputStream;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

/**
 * Guards the fix for the production incident where POST /auth/token request
 * bodies — carrying live client_credentials secrets in cleartext — were written
 * to CloudWatch at INFO by this filter.
 */
@ExtendWith(MockitoExtension.class)
class LoggingFilterTest {

    private static final String LIVE_SECRET = "s3cr3t-live-value-do-not-log";

    @Mock
    private ContainerRequestContext requestContext;

    @Mock
    private UriInfo uriInfo;

    private final LoggingFilter filter = new LoggingFilter();

    /**
     * The regression guard: on the old filter this test fails, because the old
     * code buffered the entity stream for every POST+JSON request including the
     * token endpoint, and wrote the body to the log.
     */
    @ParameterizedTest
    @ValueSource(strings = {"auth/token", "/auth/token"})
    void tokenEndpointBodyIsNeverReadOrLogged(String path) throws Exception {
        String tokenRequest = "{\"client_id\":\"guest-registration-kiosk\","
                + "\"client_secret\":\"" + LIVE_SECRET + "\"}";
        lenient().when(uriInfo.getPath()).thenReturn(path);
        lenient().when(requestContext.getUriInfo()).thenReturn(uriInfo);
        lenient().when(requestContext.getMethod()).thenReturn("POST");
        lenient().when(requestContext.getMediaType()).thenReturn(MediaType.APPLICATION_JSON_TYPE);
        // Stubbed so the old, leaking implementation reaches the verification below
        // with a clean failure message rather than an NPE.
        lenient().when(requestContext.getEntityStream()).thenReturn(
                new ByteArrayInputStream(tokenRequest.getBytes(StandardCharsets.UTF_8)));

        filter.filter(requestContext);

        // Never buffered => the secret exists nowhere in this filter, at any log level.
        verify(requestContext, never()).getEntityStream();
        verify(requestContext, never()).setEntityStream(any(InputStream.class));
    }

    @Test
    void tokenEndpointIsRecognisedRegardlessOfPathShape() {
        assertTrue(LoggingFilter.isCredentialEndpoint("auth/token"));
        assertTrue(LoggingFilter.isCredentialEndpoint("/auth/token"));
        assertTrue(LoggingFilter.isCredentialEndpoint("api/auth/token"));
        assertFalse(LoggingFilter.isCredentialEndpoint("auth/tokenized-thing"));
        assertFalse(LoggingFilter.isCredentialEndpoint("users/search"));
        assertFalse(LoggingFilter.isCredentialEndpoint(null));
    }

    @Test
    void clientSecretIsRedactedButClientIdIsKept() {
        String body = "{\"client_id\":\"guest-registration-kiosk\",\"client_secret\":\"" + LIVE_SECRET + "\"}";

        String logged = LoggingFilter.formatBodyForLog(body.getBytes(StandardCharsets.UTF_8));

        assertFalse(logged.contains(LIVE_SECRET), "secret leaked into log line: " + logged);
        assertTrue(logged.contains("guest-registration-kiosk"), "client_id should stay legible");
        assertEquals("{\"client_id\":\"guest-registration-kiosk\",\"client_secret\":\"***REDACTED***\"}", logged);
    }

    @Test
    void otherSecretBearingKeysAreRedactedByDefault() {
        String body = "{\"password\":\"" + LIVE_SECRET + "\","
                + "\"Authorization\":\"Bearer " + LIVE_SECRET + "\","
                + "\"apiKey\":\"" + LIVE_SECRET + "\","
                + "\"refresh_token\":\"" + LIVE_SECRET + "\","
                + "\"cpr\":\"010190-1234\","
                + "\"username\":\"hans\"}";

        String logged = LoggingFilter.redactSecrets(body);

        assertFalse(logged.contains(LIVE_SECRET), "secret leaked into log line: " + logged);
        assertFalse(logged.contains("010190-1234"), "cpr leaked into log line: " + logged);
        assertTrue(logged.contains("\"username\":\"hans\""), "non-secret fields should survive");
    }

    @Test
    void nonSecretKeysThatMerelyStartWithASecretWordAreUntouched() {
        String body = "{\"tokenTtlSeconds\":3600,\"passwordChangedAt\":\"2026-08-20\"}";

        assertEquals(body, LoggingFilter.redactSecrets(body));
    }

    /**
     * Redaction must happen before the line is shortened: shortening first would
     * still emit a usable prefix of the secret.
     */
    @Test
    void redactionHappensBeforeTheLineIsShortened() {
        StringBuilder padding = new StringBuilder();
        for (int i = 0; i < 40; i++) padding.append("\"pad").append(i).append("\":\"x\",");
        String body = "{\"client_secret\":\"" + LIVE_SECRET + "\"," + padding + "\"tail\":\"end\"}";

        String logged = LoggingFilter.formatBodyForLog(body.getBytes(StandardCharsets.UTF_8));

        assertTrue(logged.contains("[shortened,"), "expected a shortened line, got: " + logged);
        assertFalse(logged.contains(LIVE_SECRET), "secret leaked into log line: " + logged);
        assertFalse(logged.contains("s3cr3t"), "secret prefix leaked into log line: " + logged);
    }

    @Test
    void lineBreaksInCallerControlledValuesCannotForgeLogLines() {
        // UriInfo.getPath() is URL-decoded, so %0A arrives as a real newline.
        assertEquals("users/_INFO  [forged] admin login",
                LoggingFilter.sanitiseForLog("users/\nINFO  [forged] admin login"));
        assertEquals("a__b", LoggingFilter.sanitiseForLog("a\r\nb"));
        assertEquals("users/search", LoggingFilter.sanitiseForLog("users/search"));
    }

    @Test
    void nonCredentialJsonPostStillReadsAndRestoresTheStream() throws Exception {
        // Sanity check that the filter is not simply inert for everything: a normal
        // POST still runs through the logging path (body read only when DEBUG is on,
        // but the path/header lines are always emitted without throwing).
        lenient().when(uriInfo.getPath()).thenReturn("users/search");
        lenient().when(requestContext.getUriInfo()).thenReturn(uriInfo);
        lenient().when(requestContext.getMethod()).thenReturn("POST");
        lenient().when(requestContext.getMediaType()).thenReturn(MediaType.APPLICATION_JSON_TYPE);
        lenient().when(requestContext.getHeaderString("Content-Type")).thenReturn("application/json");
        lenient().when(requestContext.getEntityStream())
                .thenReturn(InputStream.nullInputStream());

        filter.filter(requestContext);

        verify(requestContext).getHeaderString("X-Requested-By");
    }
}
