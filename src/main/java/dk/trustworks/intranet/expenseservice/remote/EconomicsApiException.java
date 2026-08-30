package dk.trustworks.intranet.expenseservice.remote;

/**
 * Thrown by {@link EconomicsErrorMapper} for every non-2xx e-conomic response other
 * than 404 (ignored by the mapper) and 429 (which becomes
 * {@link EconomicsRateLimitException}).
 *
 * <p>It exists because the mapper is registered on the e-conomic rest clients, so a
 * failing call <em>throws</em> instead of returning the {@code Response} its method
 * signature promises. Callers that want to branch on the vendor's status or error body
 * therefore cannot read them off a response — before this type they had to parse them
 * back out of the message string, which is why
 * {@code EconomicsService.checkVoucherExists} greps for {@code "HTTP 404"}. Carrying
 * {@code status} and {@code body} as fields makes that branching honest.
 *
 * <p>Like {@link EconomicsRateLimitException} it deliberately extends
 * {@link RuntimeException}, and the message is byte-identical to the plain
 * {@code RuntimeException} it replaces, so every existing
 * {@code catch (Exception | RuntimeException)} and every message-matching caller keeps
 * behaving exactly as before.
 */
public class EconomicsApiException extends RuntimeException {

    private static final long serialVersionUID = 1L;

    private final int status;
    private final String body;

    public EconomicsApiException(String message, int status, String body) {
        super(message);
        this.status = status;
        this.body = body;
    }

    /** The HTTP status e-conomic returned. */
    public int getStatus() {
        return status;
    }

    /** The raw response body, or {@code null} when it could not be read. */
    public String getBody() {
        return body;
    }
}
