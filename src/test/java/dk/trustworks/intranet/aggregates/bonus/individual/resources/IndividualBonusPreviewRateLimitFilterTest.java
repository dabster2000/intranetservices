package dk.trustworks.intranet.aggregates.bonus.individual.resources;

import dk.trustworks.intranet.security.RequestHeaderHolder;
import jakarta.ws.rs.container.ContainerRequestContext;
import jakarta.ws.rs.container.ContainerResponseContext;
import jakarta.ws.rs.core.Response;
import jakarta.ws.rs.core.UriInfo;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

class IndividualBonusPreviewRateLimitFilterTest {
    private IndividualBonusPreviewRateLimitFilter filter;

    @BeforeEach
    void setUp() {
        filter = new IndividualBonusPreviewRateLimitFilter();
        filter.requestHeaderHolder = new RequestHeaderHolder();
        filter.requestHeaderHolder.setUserUuid("actor-a");
    }

    @Test
    void sixthConcurrentPreviewIsRejected_untilAResponseReleasesCapacity() {
        for (int i = 0; i < 5; i++) filter.filter(request());
        ContainerRequestContext rejected = request();
        filter.filter(rejected);
        assert429(rejected);

        ContainerRequestContext completed = request();
        when(completed.getProperty(anyString())).thenReturn("actor-a");
        filter.filter(completed, mock(ContainerResponseContext.class));

        ContainerRequestContext accepted = request();
        filter.filter(accepted);
        verify(accepted, never()).abortWith(any());
    }

    @Test
    void thirtyStartsPerWindowAreAllowed_andThirtyFirstIsRejected() {
        for (int i = 0; i < 30; i++) {
            ContainerRequestContext request = request();
            filter.filter(request);
            when(request.getProperty(anyString())).thenReturn("actor-a");
            filter.filter(request, mock(ContainerResponseContext.class));
        }
        ContainerRequestContext rejected = request();
        filter.filter(rejected);
        assert429(rejected);
    }

    private static void assert429(ContainerRequestContext request) {
        ArgumentCaptor<Response> response = ArgumentCaptor.forClass(Response.class);
        verify(request).abortWith(response.capture());
        assertEquals(429, response.getValue().getStatus());
    }

    private static ContainerRequestContext request() {
        ContainerRequestContext request = mock(ContainerRequestContext.class);
        UriInfo uri = mock(UriInfo.class);
        when(request.getMethod()).thenReturn("POST");
        when(request.getUriInfo()).thenReturn(uri);
        when(uri.getPath()).thenReturn("individual-bonuses/preview");
        return request;
    }
}
