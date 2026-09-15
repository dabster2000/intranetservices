package dk.trustworks.intranet.security;

import dk.trustworks.intranet.LoggingFilter;
import dk.trustworks.intranet.aggregates.conference.services.ConferenceDownloadCatalog;
import jakarta.ws.rs.container.ContainerRequestContext;
import jakarta.ws.rs.core.UriInfo;
import org.eclipse.microprofile.jwt.JsonWebToken;
import org.jboss.logging.MDC;
import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class ConferenceDownloadPrivacyTest {
    private static final String BASE = "/knowledge/conferences/" + ConferenceDownloadCatalog.CONFERENCE_UUID + "/downloads";

    @Test
    void requestLoggerDoesNotInspectBodyOrHeadersEvenForJsonRequests() throws Exception {
        var request = mock(ContainerRequestContext.class);
        var uri = mock(UriInfo.class);
        when(request.getUriInfo()).thenReturn(uri);
        when(uri.getPath()).thenReturn(BASE + "/test-new-black");
        when(request.getMethod()).thenReturn("POST");

        new LoggingFilter().filter(request);

        verify(request, never()).getMediaType();
        verify(request, never()).getHeaderString(anyString());
        verify(request, never()).getHeaders();
        verify(request, never()).getEntityStream();
        verify(request, never()).setEntityStream(any());
    }

    @Test
    void ignoresSuppliedIdentityOnAnonymousDownloadRequests() throws Exception {
        var interceptor = new HeaderInterceptor();
        interceptor.jwt = mock(JsonWebToken.class);
        interceptor.uriInfo = mock(UriInfo.class);
        interceptor.requestHeaderHolder = mock(RequestHeaderHolder.class);
        var request = mock(ContainerRequestContext.class);
        when(interceptor.uriInfo.getPath()).thenReturn(BASE + "/test-new-black");
        when(request.getMethod()).thenReturn("POST");
        MDC.put("userUuid", "previous-actor");

        interceptor.filter(request);

        verifyNoInteractions(interceptor.jwt);
        verify(request, never()).getHeaders();
        verify(interceptor.uriInfo, never()).getQueryParameters();
        verify(interceptor.requestHeaderHolder).setUserUuid("anonymous");
        assertNull(MDC.get("userUuid"));
    }

    @Test
    void downloadIdentityExceptionDoesNotMatchOtherEndpoints() {
        assertTrue(ConferenceDownloadCatalog.isAnonymousRequestPath(BASE + "/test-new-black"));
        assertTrue(ConferenceDownloadCatalog.isAnonymousRequestPath(BASE.substring(1) + "/unknown/"));
        assertFalse(ConferenceDownloadCatalog.isAnonymousRequestPath(BASE));
        assertFalse(ConferenceDownloadCatalog.isAnonymousRequestPath(BASE + "/"));
        assertFalse(ConferenceDownloadCatalog.isAnonymousRequestPath(BASE + "/test-new-black/extra"));
        assertFalse(ConferenceDownloadCatalog.isAnonymousRequestPath("/knowledge/conferences/other/downloads/test-new-black"));
        assertFalse(ConferenceDownloadCatalog.isAnonymousRequestPath(null));
    }

    @Test
    void accessLogExclusionCoversDownloadsButPreservesOtherLogs() throws Exception {
        String configuration = Files.readString(Path.of("src/main/resources/application.yml"));
        var match = Pattern.compile("exclude-pattern: '([^']+)'").matcher(configuration);
        assertTrue(match.find(), "The anonymous counter must be excluded from IP access logs");
        Pattern exclusion = Pattern.compile(match.group(1));
        for (String presentation : ConferenceDownloadCatalog.PRESENTATION_IDS) {
            assertTrue(exclusion.matcher(BASE + "/" + presentation).matches());
        }
        assertTrue(exclusion.matcher(BASE + "/unknown").matches());
        assertFalse(exclusion.matcher(BASE).matches());
        assertFalse(exclusion.matcher("/knowledge/conferences/other/downloads/test-new-black").matches());
        assertFalse(exclusion.matcher("/users").matches());
    }
}
