package dk.trustworks.intranet.utils.client;

import jakarta.ws.rs.core.Response;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class NextsignResponseExceptionMapperTest {

    @Mock
    private Response response;

    @Mock
    private Response.StatusType statusType;

    @Test
    void errorMappingUsesStatusWithoutReadingSensitiveResponseBody() {
        when(response.getStatus()).thenReturn(404);
        when(response.getStatusInfo()).thenReturn(statusType);
        when(statusType.getReasonPhrase()).thenReturn("Not Found");

        NextsignResponseExceptionMapper.NextsignApiException error =
            new NextsignResponseExceptionMapper().toThrowable(response);

        assertEquals(404, error.getStatusCode());
        assertEquals("Not Found", error.getStatusInfo());
        assertEquals("Nextsign API error 404 Not Found", error.getMessage());
        verify(response, never()).hasEntity();
        verify(response, never()).getEntity();
        verify(response, never()).readEntity(String.class);
    }
}
