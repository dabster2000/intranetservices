package dk.trustworks.intranet.aggregates.invoice.economics.book;

import jakarta.ws.rs.BadRequestException;
import jakarta.ws.rs.WebApplicationException;
import jakarta.ws.rs.core.MultivaluedMap;
import jakarta.ws.rs.core.Response;
import lombok.extern.jbosslog.JBossLog;
import org.eclipse.microprofile.rest.client.ext.ResponseExceptionMapper;

import java.io.InputStream;
import java.nio.charset.StandardCharsets;

/**
 * Captures the e-conomic legacy REST booking API error response body so a failed
 * {@code POST /invoices/booked} surfaces e-conomic's actual reason instead of a
 * bare "HTTP 400 Bad Request".
 *
 * <p>Without this mapper the MicroProfile default turns a 400 into
 * {@code BadRequestException: HTTP 400 Bad Request} with no body, which hid the
 * cause of book-phase failures in production (2026-06-30: eight booking 400s for a
 * single invoice with no logged reason — {@code InvoiceFinalizationOrchestrator.bookDraft}
 * has no error log for this path either, so the response body was the only clue and
 * it was discarded).
 *
 * <p>Behaviour-preserving on purpose, mirroring the sibling
 * {@link dk.trustworks.intranet.aggregates.invoice.economics.draft.EconomicsDraftErrorMapper}:
 * <ul>
 *   <li>404 is left to the default mapper so callers keep seeing
 *       {@link jakarta.ws.rs.NotFoundException}.</li>
 *   <li>The original HTTP status is preserved: a 400 still throws a
 *       {@link BadRequestException} so the REST layer still returns 400; all other
 *       error statuses propagate via {@link WebApplicationException} carrying that
 *       same status.</li>
 * </ul>
 *
 * <p>Registered via {@code @RegisterProvider} on {@link EconomicsBookingApiClient}.
 */
@JBossLog
public class EconomicsBookingErrorMapper implements ResponseExceptionMapper<RuntimeException> {

    @Override
    public boolean handles(int status, MultivaluedMap<String, Object> headers) {
        // Leave 404 to the default mapper (preserves NotFoundException semantics).
        return status >= 400 && status != 404;
    }

    @Override
    public RuntimeException toThrowable(Response response) {
        int status = response.getStatus();
        String body = readBody(response);
        log.errorf("e-conomic booking API error — status=%d body=%s", status, body);

        String message = "e-conomic booking API HTTP " + status + ": " + body;
        if (status == 400) {
            return new BadRequestException(message, response);
        }
        return new WebApplicationException(message, response);
    }

    private static String readBody(Response response) {
        try {
            if (!response.hasEntity()) {
                return "<no response body>";
            }
            Object entity = response.getEntity();
            if (entity instanceof InputStream in) {
                return new String(in.readAllBytes(), StandardCharsets.UTF_8);
            }
            if (entity instanceof String s) {
                return s;
            }
            return response.readEntity(String.class);
        } catch (Exception e) {
            return "<failed to read body: " + e.getMessage() + ">";
        }
    }
}
