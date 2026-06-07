package dk.trustworks.intranet.aggregates.invoice.economics.draft;

import jakarta.ws.rs.BadRequestException;
import jakarta.ws.rs.WebApplicationException;
import jakarta.ws.rs.core.MultivaluedMap;
import jakarta.ws.rs.core.Response;
import lombok.extern.jbosslog.JBossLog;
import org.eclipse.microprofile.rest.client.ext.ResponseExceptionMapper;

import java.io.InputStream;
import java.nio.charset.StandardCharsets;

/**
 * Captures the e-conomic Q2C draft-invoice API error response body so failures
 * surface the actual validation message instead of a bare "HTTP 400 Bad Request".
 *
 * <p>Without this mapper the MicroProfile default turns a 400 into
 * {@code BadRequestException: HTTP 400 Bad Request} with no body, which hides
 * e-conomic's descriptive validation error (e.g. which field/line was rejected).
 *
 * <p>Behaviour-preserving on purpose:
 * <ul>
 *   <li>404 is left to the default mapper — callers treat a 404 on draft delete
 *       as "already gone" (see {@code InvoiceFinalizationOrchestrator.cancelFinalization}),
 *       so it must keep throwing {@link jakarta.ws.rs.NotFoundException}.</li>
 *   <li>The original HTTP status is preserved: a 400 still throws a
 *       {@link BadRequestException} so the REST layer still returns 400; all other
 *       error statuses propagate via {@link WebApplicationException} carrying that
 *       same status.</li>
 * </ul>
 *
 * <p>Mirrors the established convention of {@code NextsignResponseExceptionMapper}
 * and {@code EconomicsErrorMapper}. Registered via {@code @RegisterProvider} on
 * {@link EconomicsDraftInvoiceApiClient}.
 */
@JBossLog
public class EconomicsDraftErrorMapper implements ResponseExceptionMapper<RuntimeException> {

    @Override
    public boolean handles(int status, MultivaluedMap<String, Object> headers) {
        // Leave 404 to the default mapper (preserves NotFoundException semantics).
        return status >= 400 && status != 404;
    }

    @Override
    public RuntimeException toThrowable(Response response) {
        int status = response.getStatus();
        String body = readBody(response);
        log.errorf("e-conomic Q2C draft API error — status=%d body=%s", status, body);

        String message = "e-conomic Q2C draft API HTTP " + status + ": " + body;
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
