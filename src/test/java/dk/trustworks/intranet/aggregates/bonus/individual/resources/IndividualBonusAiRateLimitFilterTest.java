package dk.trustworks.intranet.aggregates.bonus.individual.resources;

import dk.trustworks.intranet.security.RequestHeaderHolder;
import jakarta.ws.rs.container.ContainerRequestContext;
import jakarta.ws.rs.core.Response;
import jakarta.ws.rs.core.UriInfo;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.time.Instant;
import java.util.concurrent.ConcurrentLinkedDeque;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class IndividualBonusAiRateLimitFilterTest {

    private IndividualBonusAiRateLimitFilter filter;
    private RequestHeaderHolder actor;
    private ContainerRequestContext request;

    @BeforeEach
    void setUp() {
        filter = new IndividualBonusAiRateLimitFilter();
        filter.requestHeaderHolder = actor = new RequestHeaderHolder();
        actor.setUserUuid("actor-a");
        request = request("POST", "individual-bonuses/generate-from-text");
    }

    @Test
    void firstTenAreAllowed_andEleventhReturns429WithRetryAfter() {
        for (int i = 0; i < 10; i++) filter.filter(request);
        verify(request, never()).abortWith(any());

        filter.filter(request);

        ArgumentCaptor<Response> response = ArgumentCaptor.forClass(Response.class);
        verify(request).abortWith(response.capture());
        assertEquals(429, response.getValue().getStatus());
        assertNotNull(response.getValue().getHeaderString("Retry-After"));
        assertTrue(Long.parseLong(response.getValue().getHeaderString("Retry-After")) > 0);
    }

    @Test
    void windowIsSliding_expiredTimestampsDoNotCount() {
        ConcurrentLinkedDeque<Instant> old = new ConcurrentLinkedDeque<>();
        for (int i = 0; i < 10; i++) old.add(Instant.now().minusSeconds(3_601));
        filter.requestLog().put("actor-a", old);

        for (int i = 0; i < 10; i++) filter.filter(request);

        verify(request, never()).abortWith(any());
        assertEquals(10, filter.requestLog().get("actor-a").size());
    }

    @Test
    void limitsPerActor_andIgnoresOtherRoutesAndMethods() {
        for (int i = 0; i < 10; i++) filter.filter(request);

        actor.setUserUuid("actor-b");
        filter.filter(request);
        filter.filter(request("GET", "individual-bonuses/generate-from-text"));
        filter.filter(request("POST", "individual-bonuses/preview"));

        verify(request, never()).abortWith(any());
        assertEquals(10, filter.requestLog().get("actor-a").size());
        assertEquals(1, filter.requestLog().get("actor-b").size());
    }

    @Test
    void concurrentRequestsCannotRacePastLimit() throws Exception {
        int calls = 20;
        ExecutorService pool = Executors.newFixedThreadPool(calls);
        CountDownLatch start = new CountDownLatch(1);
        CountDownLatch done = new CountDownLatch(calls);
        try {
            for (int i = 0; i < calls; i++) {
                pool.submit(() -> {
                    try {
                        start.await();
                        filter.filter(request);
                    } catch (InterruptedException interrupted) {
                        Thread.currentThread().interrupt();
                    } finally {
                        done.countDown();
                    }
                });
            }
            start.countDown();
            assertTrue(done.await(5, TimeUnit.SECONDS));
        } finally {
            pool.shutdownNow();
        }

        assertEquals(10, filter.requestLog().get("actor-a").size());
        verify(request, times(10)).abortWith(any());
    }

    @Test
    void anonymousActorIsRejectedInsteadOfBypassingTheLimit() {
        actor.setUserUuid("anonymous");

        filter.filter(request);

        ArgumentCaptor<Response> response = ArgumentCaptor.forClass(Response.class);
        verify(request).abortWith(response.capture());
        assertEquals(401, response.getValue().getStatus());
        assertFalse(filter.requestLog().containsKey("anonymous"));
    }

    private static ContainerRequestContext request(String method, String path) {
        ContainerRequestContext context = mock(ContainerRequestContext.class);
        UriInfo uri = mock(UriInfo.class);
        when(context.getMethod()).thenReturn(method);
        when(context.getUriInfo()).thenReturn(uri);
        when(uri.getPath()).thenReturn(path);
        return context;
    }
}
