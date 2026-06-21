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
 * Pins the typed-429 contract: only HTTP 429 maps to EconomicsRateLimitException
 * (carrying the optional Retry-After in seconds); every other status keeps the
 * plain RuntimeException with the existing "HTTP <n> from Economics:" message so
 * the three rest clients that share this mapper behave exactly as before.
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
    void maps_non_429_to_plain_runtime_exception() {
        when(response.getStatus()).thenReturn(500);
        when(response.readEntity(String.class)).thenReturn("boom");

        RuntimeException ex = new EconomicsErrorMapper().toThrowable(response);

        assertFalse(ex instanceof EconomicsRateLimitException);
        assertTrue(ex.getMessage().contains("HTTP 500 from Economics: boom"));
    }
}
