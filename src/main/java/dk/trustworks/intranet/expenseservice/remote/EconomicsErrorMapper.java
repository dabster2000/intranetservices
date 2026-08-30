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

        // HTTP 429 keeps its own type so the read-path retry executor can back off on it;
        // every other status becomes EconomicsApiException. Both messages are byte-identical
        // to what callers saw before, so message-matching callers are unaffected — the only
        // difference is that status and body are now readable as fields instead of having to
        // be parsed back out of the message.
        // readEntity(...) is called before getHeaderString(...) only because the
        // body must be consumed while the response stream is open — header access
        // order is irrelevant.
        if (status == 429) {
            Long retryAfterSeconds = parseRetryAfterSeconds(response.getHeaderString("Retry-After"));
            return new EconomicsRateLimitException(
                    "HTTP 429 from Economics: " + (body != null ? body : ""),
                    retryAfterSeconds);
        }
        return new EconomicsApiException(
                "HTTP " + status + " from Economics: " + (body != null ? body : ""), status, body);
    }

    /**
     * Parse the {@code Retry-After} header as a non-negative integer number of
     * seconds. e-conomic sends the seconds form; the HTTP-date form is treated as
     * absent (returns {@code null}) and the caller falls back to backoff.
     */
    public static Long parseRetryAfterSeconds(String headerValue) {
        if (headerValue == null || headerValue.isBlank()) return null;
        try {
            long seconds = Long.parseLong(headerValue.trim());
            return seconds >= 0 ? seconds : null;
        } catch (NumberFormatException e) {
            return null;
        }
    }
}
