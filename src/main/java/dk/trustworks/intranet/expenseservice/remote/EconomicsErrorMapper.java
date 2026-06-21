package dk.trustworks.intranet.expenseservice.remote;

import jakarta.ws.rs.core.MultivaluedMap;
import jakarta.ws.rs.core.Response;
import org.eclipse.microprofile.rest.client.ext.ResponseExceptionMapper;

public class EconomicsErrorMapper implements ResponseExceptionMapper<RuntimeException> {

    @Override
    public boolean handles(int status, MultivaluedMap<String, Object> headers) {
        // Do not map 404 to exceptions; callers often treat 404 as "not found"
        return status >= 400 && status != 404;
    }

    @Override
    public RuntimeException toThrowable(Response response) {
        int status = response.getStatus();
        String body = null;
        try {
            body = response.readEntity(String.class);
        } catch (Exception ignore) { }

        // Only 429 becomes the typed exception. The Retry-After header is read
        // lazily here (after the body) so the shared mapper's behavior for every
        // other status is byte-identical to the previous implementation.
        if (status == 429) {
            Long retryAfterSeconds = parseRetryAfterSeconds(response.getHeaderString("Retry-After"));
            return new EconomicsRateLimitException(
                    "HTTP 429 from Economics: " + (body != null ? body : ""),
                    retryAfterSeconds);
        }
        return new RuntimeException("HTTP " + status + " from Economics: " + (body != null ? body : ""));
    }

    /**
     * Parse the {@code Retry-After} header as a non-negative integer number of
     * seconds. e-conomic sends the seconds form; the HTTP-date form is treated as
     * absent (returns {@code null}) and the caller falls back to backoff.
     */
    static Long parseRetryAfterSeconds(String headerValue) {
        if (headerValue == null || headerValue.isBlank()) return null;
        try {
            long seconds = Long.parseLong(headerValue.trim());
            return seconds >= 0 ? seconds : null;
        } catch (NumberFormatException e) {
            return null;
        }
    }
}
