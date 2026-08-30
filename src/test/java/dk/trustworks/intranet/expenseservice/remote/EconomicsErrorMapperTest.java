package dk.trustworks.intranet.expenseservice.remote;

import jakarta.ws.rs.core.Response;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.when;

/**
 * Pins the mapper's typed contract: HTTP 429 maps to EconomicsRateLimitException
 * (carrying the optional Retry-After in seconds); every other status maps to
 * EconomicsApiException, which carries the status and raw body as fields. Both keep
 * the "HTTP &lt;n&gt; from Economics:" message byte-identical, so the three rest clients
 * that share this mapper — and the callers that match on that message — behave exactly
 * as before.
 */
@ExtendWith(MockitoExtension.class)
class EconomicsErrorMapperTest {

    @Mock
    Response response;

    @Test
    void maps_429_with_retry_after_to_rate_limit_exception() {
        when(response.getStatus()).thenReturn(429);
        when(response.readEntity(String.class)).thenReturn("{\"error\":\"throttled\"}");
        when(response.getHeaderString("Retry-After")).thenReturn("7");

        RuntimeException ex = new EconomicsErrorMapper().toThrowable(response);

        assertInstanceOf(EconomicsRateLimitException.class, ex);
        assertEquals(7L, ((EconomicsRateLimitException) ex).getRetryAfterSeconds());
    }

    @Test
    void maps_429_without_retry_after_to_null_seconds() {
        when(response.getStatus()).thenReturn(429);
        when(response.readEntity(String.class)).thenReturn("");
        when(response.getHeaderString("Retry-After")).thenReturn(null);

        RuntimeException ex = new EconomicsErrorMapper().toThrowable(response);

        assertInstanceOf(EconomicsRateLimitException.class, ex);
        assertNull(((EconomicsRateLimitException) ex).getRetryAfterSeconds());
    }

    @Test
    void ignores_non_numeric_retry_after() {
        when(response.getStatus()).thenReturn(429);
        when(response.readEntity(String.class)).thenReturn("");
        when(response.getHeaderString("Retry-After")).thenReturn("Wed, 21 Oct 2026 07:28:00 GMT");

        RuntimeException ex = new EconomicsErrorMapper().toThrowable(response);

        assertInstanceOf(EconomicsRateLimitException.class, ex);
        assertNull(((EconomicsRateLimitException) ex).getRetryAfterSeconds());
    }

    @Test
    void maps_non_429_to_economics_api_exception() {
        when(response.getStatus()).thenReturn(500);
        when(response.readEntity(String.class)).thenReturn("boom");

        RuntimeException ex = new EconomicsErrorMapper().toThrowable(response);

        assertFalse(ex instanceof EconomicsRateLimitException);
        assertInstanceOf(EconomicsApiException.class, ex);
        assertTrue(ex.getMessage().contains("HTTP 500 from Economics: boom"));
    }

    /**
     * The status and body must survive as fields, not only inside the message. This is the
     * whole point of the type: the mapper is registered on EconomicsAPI, so a rejected call
     * throws instead of returning the Response its signature promises, and a caller that
     * needs to branch on the vendor's status or error body has nowhere else to read them.
     */
    @Test
    void non_429_carries_the_status_and_raw_body_as_fields() {
        when(response.getStatus()).thenReturn(400);
        when(response.readEntity(String.class)).thenReturn("{\"errorCode\":\"E04300\"}");

        EconomicsApiException ex =
                assertInstanceOf(EconomicsApiException.class, new EconomicsErrorMapper().toThrowable(response));

        assertEquals(400, ex.getStatus());
        assertEquals("{\"errorCode\":\"E04300\"}", ex.getBody());
    }

    /** An unreadable body must not become the string "null" or blow up the mapper. */
    @Test
    void unreadable_body_maps_to_null_body_not_the_string_null() {
        when(response.getStatus()).thenReturn(502);
        when(response.readEntity(String.class)).thenThrow(new IllegalStateException("stream closed"));

        EconomicsApiException ex =
                assertInstanceOf(EconomicsApiException.class, new EconomicsErrorMapper().toThrowable(response));

        assertEquals(502, ex.getStatus());
        assertNull(ex.getBody());
        assertTrue(ex.getMessage().contains("HTTP 502 from Economics: "));
    }
}
