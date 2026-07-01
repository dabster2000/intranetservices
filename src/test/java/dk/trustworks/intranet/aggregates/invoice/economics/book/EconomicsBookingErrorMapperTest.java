package dk.trustworks.intranet.aggregates.invoice.economics.book;

import jakarta.ws.rs.BadRequestException;
import jakarta.ws.rs.WebApplicationException;
import jakarta.ws.rs.core.Response;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.when;

/**
 * Pins the diagnosability contract for the e-conomic legacy booking client: a 400
 * maps to a {@link BadRequestException} whose message carries e-conomic's actual
 * response body (previously discarded as a bare "HTTP 400 Bad Request"), other error
 * statuses keep their status via {@link WebApplicationException}, and 404 is left to
 * the default mapper.
 */
@ExtendWith(MockitoExtension.class)
class EconomicsBookingErrorMapperTest {

    @Mock
    Response response;

    @Test
    void maps_400_to_BadRequestException_carrying_economic_body() {
        when(response.getStatus()).thenReturn(400);
        // BadRequestException(String, Response) validates the response's 4xx family.
        when(response.getStatusInfo()).thenReturn(Response.Status.BAD_REQUEST);
        when(response.hasEntity()).thenReturn(true);
        when(response.getEntity()).thenReturn("{\"errorCode\":\"DraftInvoiceValidationFailed\"}");

        RuntimeException ex = new EconomicsBookingErrorMapper().toThrowable(response);

        assertInstanceOf(BadRequestException.class, ex);
        assertTrue(ex.getMessage().contains("DraftInvoiceValidationFailed"),
                "message must surface e-conomic's response body; got: " + ex.getMessage());
    }

    @Test
    void maps_non_400_error_to_WebApplicationException_preserving_status() {
        when(response.getStatus()).thenReturn(500);
        when(response.hasEntity()).thenReturn(true);
        when(response.getEntity()).thenReturn("upstream boom");

        RuntimeException ex = new EconomicsBookingErrorMapper().toThrowable(response);

        assertFalse(ex instanceof BadRequestException);
        assertInstanceOf(WebApplicationException.class, ex);
        assertTrue(ex.getMessage().contains("HTTP 500"),
                "message should carry the upstream status; got: " + ex.getMessage());
    }

    @Test
    void leaves_404_to_default_mapper_but_handles_other_4xx_5xx() {
        EconomicsBookingErrorMapper mapper = new EconomicsBookingErrorMapper();
        assertFalse(mapper.handles(404, null), "404 must be left to the default mapper");
        assertTrue(mapper.handles(400, null));
        assertTrue(mapper.handles(500, null));
    }
}
