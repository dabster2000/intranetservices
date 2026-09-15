package dk.trustworks.intranet.security;

import dk.trustworks.intranet.LoggingFilter;
import dk.trustworks.intranet.aggregates.conference.services.ConferenceUnsubscribeService;
import jakarta.ws.rs.container.ContainerRequestContext;
import jakarta.ws.rs.core.UriInfo;
import org.eclipse.microprofile.jwt.JsonWebToken;
import org.jboss.logging.MDC;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class ConferenceUnsubscribePrivacyTest {
    @Test
    void loggerNeverReadsCapabilityHeadersOrRequestBody() throws Exception {
        var request = mock(ContainerRequestContext.class);
        var uri = mock(UriInfo.class);
        when(request.getUriInfo()).thenReturn(uri);
        when(uri.getPath()).thenReturn("/knowledge/conferences/unsubscribe");
        new LoggingFilter().filter(request);
        verify(request, never()).getHeaderString(anyString());
        verify(request, never()).getHeaders();
        verify(request, never()).getMediaType();
        verify(request, never()).getEntityStream();
    }

    @Test
    void capabilityWithdrawalNeverAcquiresCallerEmployeeIdentity() throws Exception {
        var interceptor = new HeaderInterceptor();
        interceptor.jwt = mock(JsonWebToken.class);
        interceptor.uriInfo = mock(UriInfo.class);
        interceptor.requestHeaderHolder = mock(RequestHeaderHolder.class);
        var request = mock(ContainerRequestContext.class);
        when(interceptor.uriInfo.getPath()).thenReturn("knowledge/conferences/unsubscribe");
        MDC.put("userUuid", "previous-actor");
        interceptor.filter(request);
        verifyNoInteractions(interceptor.jwt);
        verify(request, never()).getHeaders();
        verify(interceptor.uriInfo, never()).getQueryParameters();
        verify(interceptor.requestHeaderHolder).setUserUuid("anonymous");
        verify(interceptor.requestHeaderHolder).setActingForUuid(null);
        assertNull(MDC.get("userUuid"));
    }

    @Test
    void privacyExceptionsAreBoundedToExactFixedRoute() {
        assertTrue(ConferenceUnsubscribeService.isUnsubscribePath("/knowledge/conferences/unsubscribe/"));
        assertFalse(ConferenceUnsubscribeService.isUnsubscribePath("knowledge/conferences/unsubscribe/admin"));
        assertFalse(ConferenceUnsubscribeService.isUnsubscribePath("knowledge/conferences/unsubscribe-token"));
        assertFalse(ConferenceUnsubscribeService.isUnsubscribePath("knowledge/conferences/message"));
    }
}
