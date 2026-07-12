package dk.trustworks.intranet.utils.client;

import jakarta.ws.rs.client.ClientRequestContext;
import jakarta.ws.rs.client.ClientResponseContext;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.io.InputStream;
import java.net.URI;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.verifyNoMoreInteractions;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class NextsignLoggingFilterTest {

    @Mock
    private ClientRequestContext requestContext;

    @Mock
    private ClientResponseContext responseContext;

    @InjectMocks
    private NextsignLoggingFilter filter;

    @Test
    void requestFilterLogsMethodAndUriWithoutAccessingHeaders() throws Exception {
        when(requestContext.getMethod()).thenReturn("GET");
        when(requestContext.getUri()).thenReturn(URI.create("https://api.nextsign.dk/api/cases/case-id"));

        filter.filter(requestContext);

        verify(requestContext).getMethod();
        verify(requestContext).getUri();
        verify(requestContext, never()).getHeaders();
        verify(requestContext, never()).getStringHeaders();
        verifyNoMoreInteractions(requestContext);
    }

    @Test
    void responseFilterLogsStatusWithoutAccessingHeadersOrBody() throws Exception {
        when(responseContext.getStatus()).thenReturn(200);

        filter.filter(requestContext, responseContext);

        verify(responseContext).getStatus();
        verify(responseContext, never()).getStatusInfo();
        verify(responseContext, never()).getHeaders();
        verify(responseContext, never()).hasEntity();
        verify(responseContext, never()).getEntityStream();
        verify(responseContext, never()).setEntityStream(any(InputStream.class));
        verifyNoMoreInteractions(responseContext);
        verifyNoInteractions(requestContext);
    }
}
