package dk.trustworks.intranet.sharepoint.client;

import jakarta.ws.rs.core.Response;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * The mapper's throttling contract, added after the production Graph 429
 * {@code MailboxConcurrency} burst of 2026-08-15: the response's
 * {@code Retry-After} was being discarded here, so the free/busy sweep had
 * nothing to back off by and retried instantly — inside the very overload
 * window Graph was asking it to leave.
 * <p>
 * Plain unit test (mocked {@link Response}) — no Quarkus boot, no JAX-RS
 * RuntimeDelegate, so it runs in the DB-free tier that gates deploys.
 */
class GraphResponseExceptionMapperRetryAfterTest {

    private final GraphResponseExceptionMapper mapper = new GraphResponseExceptionMapper();

    @Test
    void retryAfterSeconds_rideOnTheException() {
        var error = mapper.toThrowable(response(429, "Too Many Requests",
                "{\"error\":{\"code\":\"ApplicationThrottled\","
                        + "\"message\":\"Application is over its MailboxConcurrency limit\"}}",
                "12", null));

        assertEquals(429, error.getStatusCode());
        assertTrue(error.isThrottled());
        assertEquals(12, error.getRetryAfterSeconds());
    }

    @Test
    void microsoftMillisecondHeader_isReadAndRoundedUp() {
        // Graph sometimes sends only x-ms-retry-after-ms. Rounding UP matters:
        // coming back early is what earned the 429 in the first place.
        var error = mapper.toThrowable(
                response(429, "Too Many Requests", "{}", null, "2500"));

        assertEquals(3, error.getRetryAfterSeconds());
    }

    @Test
    void unparseableOrAbsentRetryAfter_isNull_andNeverThrows() {
        assertNull(mapper.toThrowable(response(429, "Too Many Requests", "{}", null, null))
                .getRetryAfterSeconds());
        // The HTTP-date form is deliberately not parsed — guessing a clock
        // offset is worse than falling back to the caller's own default.
        assertNull(mapper.toThrowable(response(429, "Too Many Requests", "{}",
                "Fri, 15 Aug 2026 07:44:00 GMT", null)).getRetryAfterSeconds());
    }

    @Test
    void otherStatuses_keepTheirExistingPredicates() {
        // Blast-radius guard: SharePoint migration, employee docs and the
        // recruitment recheck all branch on these.
        var notFound = mapper.toThrowable(response(404, "Not Found",
                "{\"error\":{\"code\":\"ErrorInvalidUser\"}}", null, null));
        assertTrue(notFound.isNotFound());
        assertFalse(notFound.isThrottled());
        assertNull(notFound.getRetryAfterSeconds());

        var serverError = mapper.toThrowable(response(500, "Internal Server Error", "{}", null, null));
        assertTrue(serverError.isServerError());
        assertFalse(serverError.isThrottled());

        assertTrue(mapper.toThrowable(response(403, "Forbidden", "{}", null, null))
                .isUnauthorized());
    }

    @Test
    void theGraphMessageIsStillLiftedOutOfTheBody() {
        // Reading headers must not have broken the entity draining below it.
        var error = mapper.toThrowable(response(429, "Too Many Requests",
                "{\"error\":{\"code\":\"ApplicationThrottled\","
                        + "\"message\":\"Application is over its MailboxConcurrency limit\"}}",
                "5", null));

        assertTrue(error.getMessage().contains("Application is over its MailboxConcurrency limit"),
                "actual message was: " + error.getMessage());
    }

    @Test
    void handlesEveryErrorStatus_unchanged() {
        assertTrue(mapper.handles(429, null));
        assertTrue(mapper.handles(404, null));
        assertFalse(mapper.handles(200, null));
    }

    private static Response response(int status, String reason, String body,
                                     String retryAfter, String retryAfterMs) {
        Response response = mock(Response.class);
        Response.StatusType statusType = mock(Response.StatusType.class);
        when(statusType.getReasonPhrase()).thenReturn(reason);
        when(response.getStatus()).thenReturn(status);
        when(response.getStatusInfo()).thenReturn(statusType);
        when(response.getHeaderString("Retry-After")).thenReturn(retryAfter);
        when(response.getHeaderString("x-ms-retry-after-ms")).thenReturn(retryAfterMs);
        when(response.hasEntity()).thenReturn(true);
        when(response.getEntity()).thenReturn(body);
        return response;
    }
}
