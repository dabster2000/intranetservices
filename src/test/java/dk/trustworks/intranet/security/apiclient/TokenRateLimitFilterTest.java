package dk.trustworks.intranet.security.apiclient;

import jakarta.ws.rs.container.ContainerRequestContext;
import jakarta.ws.rs.core.MultivaluedHashMap;
import jakarta.ws.rs.core.Response;
import jakarta.ws.rs.core.UriInfo;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.Instant;
import java.util.Deque;
import java.util.concurrent.ConcurrentLinkedDeque;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

/**
 * Unit tests for {@link TokenRateLimitFilter}.
 * Tests sliding window rate limiting on the POST /auth/token endpoint.
 */
@ExtendWith(MockitoExtension.class)
class TokenRateLimitFilterTest {

    @Mock
    private ContainerRequestContext requestContext;

    @Mock
    private UriInfo uriInfo;

    private TokenRateLimitFilter filter;

    @BeforeEach
    void setUp() {
        filter = new TokenRateLimitFilter();
    }

    // -- Path matching --

    @Test
    void filter_nonTokenEndpoint_doesNotRateLimit() {
        when(requestContext.getMethod()).thenReturn("POST");
        when(requestContext.getUriInfo()).thenReturn(uriInfo);
        when(uriInfo.getPath()).thenReturn("/api/users");

        filter.filter(requestContext);

        verify(requestContext, never()).abortWith(any());
    }

    @Test
    void filter_getOnTokenEndpoint_doesNotRateLimit() {
        when(requestContext.getMethod()).thenReturn("GET");

        filter.filter(requestContext);

        verify(requestContext, never()).abortWith(any());
    }

    @Test
    void filter_postOnDifferentPath_doesNotRateLimit() {
        when(requestContext.getMethod()).thenReturn("POST");
        when(requestContext.getUriInfo()).thenReturn(uriInfo);
        when(uriInfo.getPath()).thenReturn("/api/contracts");

        filter.filter(requestContext);

        verify(requestContext, never()).abortWith(any());
    }

    // -- IP extraction --

    @Test
    void extractSourceIp_xForwardedFor_usesLastIp() {
        when(requestContext.getHeaderString("X-Forwarded-For")).thenReturn("1.2.3.4, 5.6.7.8");

        String ip = filter.extractSourceIp(requestContext);

        assertEquals("5.6.7.8", ip);
    }

    @Test
    void extractSourceIp_noXForwardedFor_fallsBackToProperty() {
        when(requestContext.getHeaderString("X-Forwarded-For")).thenReturn(null);
        when(requestContext.getProperty("org.jboss.resteasy.request.remote.address"))
                .thenReturn("10.0.0.1");

        String ip = filter.extractSourceIp(requestContext);

        assertEquals("10.0.0.1", ip);
    }

    @Test
    void extractSourceIp_noHeaders_returnsUnknown() {
        when(requestContext.getHeaderString("X-Forwarded-For")).thenReturn(null);
        when(requestContext.getProperty("org.jboss.resteasy.request.remote.address"))
                .thenReturn(null);

        String ip = filter.extractSourceIp(requestContext);

        assertEquals("unknown", ip);
    }

    @Test
    void extractSourceIp_blankXForwardedFor_fallsBack() {
        when(requestContext.getHeaderString("X-Forwarded-For")).thenReturn("   ");
        when(requestContext.getProperty("org.jboss.resteasy.request.remote.address"))
                .thenReturn("10.0.0.2");

        String ip = filter.extractSourceIp(requestContext);

        assertEquals("10.0.0.2", ip);
    }

    // -- Rate limiting behavior --

    @Test
    void filter_firstRequest_isAllowed() {
        configureAsTokenEndpoint("1.2.3.4");

        filter.filter(requestContext);

        verify(requestContext, never()).abortWith(any());
        assertEquals(1, filter.getRequestLog().get("1.2.3.4").size());
    }

    @Test
    void filter_tenthRequest_isAllowed() {
        configureAsTokenEndpoint("1.2.3.4");

        for (int i = 0; i < 10; i++) {
            reset(requestContext);
            configureAsTokenEndpoint("1.2.3.4");
            filter.filter(requestContext);
        }

        verify(requestContext, never()).abortWith(any());
        assertEquals(10, filter.getRequestLog().get("1.2.3.4").size());
    }

    @Test
    void filter_eleventhRequest_returns429() {
        configureAsTokenEndpoint("1.2.3.4");

        // First 10 requests should succeed
        for (int i = 0; i < 10; i++) {
            reset(requestContext);
            configureAsTokenEndpoint("1.2.3.4");
            filter.filter(requestContext);
        }

        // 11th request should be rate limited
        reset(requestContext);
        configureAsTokenEndpoint("1.2.3.4");
        filter.filter(requestContext);

        ArgumentCaptor<Response> responseCaptor = ArgumentCaptor.forClass(Response.class);
        verify(requestContext).abortWith(responseCaptor.capture());

        Response response = responseCaptor.getValue();
        assertEquals(429, response.getStatus());
        assertNotNull(response.getHeaderString("Retry-After"));
        String body = (String) response.getEntity();
        assertTrue(body.contains("rate_limit_exceeded"));
    }

    @Test
    void filter_differentIps_haveIndependentBuckets() {
        // Fill up IP A with 10 requests
        for (int i = 0; i < 10; i++) {
            reset(requestContext);
            configureAsTokenEndpoint("1.1.1.1");
            filter.filter(requestContext);
        }

        // IP B should still be allowed
        reset(requestContext);
        configureAsTokenEndpoint("2.2.2.2");
        filter.filter(requestContext);

        verify(requestContext, never()).abortWith(any());
    }

    @Test
    void filter_expiredTimestamps_arePurgedFromWindow() {
        configureAsTokenEndpoint("1.2.3.4");

        // Pre-populate with 10 timestamps that are older than the window
        Deque<Instant> timestamps = new ConcurrentLinkedDeque<>();
        Instant pastTime = Instant.now().minus(TokenRateLimitFilter.WINDOW_DURATION.plusSeconds(10));
        for (int i = 0; i < 10; i++) {
            timestamps.addLast(pastTime.plusSeconds(i));
        }
        filter.getRequestLog().put("1.2.3.4", timestamps);

        // This request should succeed because old timestamps are purged
        filter.filter(requestContext);

        verify(requestContext, never()).abortWith(any());
    }

    @Test
    void filter_429Response_containsRetryAfterHeader() {
        configureAsTokenEndpoint("1.2.3.4");

        // Pre-populate with 10 recent timestamps
        Deque<Instant> timestamps = new ConcurrentLinkedDeque<>();
        Instant now = Instant.now();
        for (int i = 0; i < 10; i++) {
            timestamps.addLast(now.minusSeconds(5));
        }
        filter.getRequestLog().put("1.2.3.4", timestamps);

        filter.filter(requestContext);

        ArgumentCaptor<Response> responseCaptor = ArgumentCaptor.forClass(Response.class);
        verify(requestContext).abortWith(responseCaptor.capture());

        Response response = responseCaptor.getValue();
        String retryAfter = response.getHeaderString("Retry-After");
        assertNotNull(retryAfter, "Retry-After header must be present");
        long retryAfterSeconds = Long.parseLong(retryAfter);
        assertTrue(retryAfterSeconds > 0, "Retry-After must be positive");
        assertTrue(retryAfterSeconds <= 60, "Retry-After must be within window duration");
    }

    @Test
    void filter_429Response_hasJsonContentType() {
        configureAsTokenEndpoint("1.2.3.4");

        // Fill up the bucket
        Deque<Instant> timestamps = new ConcurrentLinkedDeque<>();
        for (int i = 0; i < 10; i++) {
            timestamps.addLast(Instant.now());
        }
        filter.getRequestLog().put("1.2.3.4", timestamps);

        filter.filter(requestContext);

        ArgumentCaptor<Response> responseCaptor = ArgumentCaptor.forClass(Response.class);
        verify(requestContext).abortWith(responseCaptor.capture());

        Response response = responseCaptor.getValue();
        assertEquals("application/json", response.getMediaType().toString());
    }

    @Test
    void filter_rateLimitedRequest_doesNotAddTimestamp() {
        configureAsTokenEndpoint("1.2.3.4");

        // Pre-populate with exactly 10 recent timestamps
        Deque<Instant> timestamps = new ConcurrentLinkedDeque<>();
        for (int i = 0; i < 10; i++) {
            timestamps.addLast(Instant.now());
        }
        filter.getRequestLog().put("1.2.3.4", timestamps);

        filter.filter(requestContext);

        // Should still be 10, not 11 (rate-limited request is not recorded)
        assertEquals(10, filter.getRequestLog().get("1.2.3.4").size());
    }

    // -- Eviction --

    @Test
    void filter_exceedingMaxTrackedIps_evictsStaleEntries() {
        // Fill up beyond MAX_TRACKED_IPS with stale entries
        Instant staleTime = Instant.now().minus(TokenRateLimitFilter.EVICTION_AGE.plusSeconds(60));
        for (int i = 0; i <= TokenRateLimitFilter.MAX_TRACKED_IPS; i++) {
            Deque<Instant> deque = new ConcurrentLinkedDeque<>();
            deque.addLast(staleTime);
            filter.getRequestLog().put("stale-" + i, deque);
        }

        // New request should trigger eviction
        configureAsTokenEndpoint("fresh-ip");
        filter.filter(requestContext);

        verify(requestContext, never()).abortWith(any());
        // Stale entries should have been evicted
        assertTrue(filter.getRequestLog().size() <= TokenRateLimitFilter.MAX_TRACKED_IPS + 1,
                "Stale entries should be evicted when exceeding max tracked IPs");
    }

    // -- Helpers --

    private void configureAsTokenEndpoint(String ip) {
        when(requestContext.getMethod()).thenReturn("POST");
        when(requestContext.getUriInfo()).thenReturn(uriInfo);
        when(uriInfo.getPath()).thenReturn("/auth/token");
        when(requestContext.getHeaderString("X-Forwarded-For")).thenReturn(ip);
    }
}
