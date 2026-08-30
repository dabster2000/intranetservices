package dk.trustworks.intranet.expenseservice.services;

import dk.trustworks.intranet.expenseservice.remote.EconomicsApiException;
import dk.trustworks.intranet.expenseservice.remote.EconomicsErrorMapper;
import dk.trustworks.intranet.expenseservice.services.EconomicsService.AttachmentRecovery;
import jakarta.ws.rs.core.Response;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Pins the two recovery paths of {@code EconomicsService.sendFile()} — re-POST under a fresh
 * idempotency key, and fall back to PATCH — against the way e-conomic's rejection actually
 * reaches them.
 *
 * <p>Both were unreachable for the lifetime of the feature. They hung off
 * {@code if (r.getStatus() == 400)}, but {@code EconomicsErrorMapper} is registered on
 * {@code EconomicsAPI} and its {@code handles()} is {@code status >= 400 && status != 404}, so
 * {@code postExpenseFile} throws rather than returning the {@code Response} its signature
 * promises. The 400 never arrived as a status, so neither branch ever ran, and the exception
 * — not being a {@code WebApplicationException} — fell through to the generic handler that
 * reports HTTP 502 "Unexpected error during attachment upload".
 *
 * <p>These tests therefore drive the whole chain (vendor response → mapper → exception →
 * recovery decision) rather than handing a body straight to the decision, because taking that
 * shortcut is exactly what hid the defect.
 */
class EconomicsAttachmentRecoveryTest {

    /** A rejection as it actually arrives: thrown by the mapper, never returned. */
    private static EconomicsApiException rejectionFromEconomic(int status, String body) {
        Response vendorResponse = mock(Response.class);
        when(vendorResponse.getStatus()).thenReturn(status);
        when(vendorResponse.readEntity(String.class)).thenReturn(body);

        return assertInstanceOf(EconomicsApiException.class,
                new EconomicsErrorMapper().toThrowable(vendorResponse),
                "the mapper must hand sendFile a status and a body, not just a message");
    }

    @Test
    void url_changed_400_survives_the_mapper_and_asks_for_a_fresh_idempotency_key() {
        EconomicsApiException e = rejectionFromEconomic(400,
                "{\"errorCode\":\"URLChanged\",\"message\":\"The idempotency key was already used "
                        + "against a different URL.\"}");

        assertEquals(400, e.getStatus());
        assertEquals(AttachmentRecovery.RETRY_NEW_IDEMPOTENCY_KEY,
                EconomicsService.attachmentRecoveryFor(e.getStatus(), e.getBody()),
                "an idempotency-key collision must re-POST under a new key, not fail the expense");
    }

    @Test
    void already_has_attachment_400_survives_the_mapper_and_asks_for_the_patch_fallback() {
        EconomicsApiException e = rejectionFromEconomic(400,
                "{\"message\":\"Voucher already has attachment\"}");

        assertEquals(400, e.getStatus());
        assertEquals(AttachmentRecovery.FALL_BACK_TO_PATCH,
                EconomicsService.attachmentRecoveryFor(e.getStatus(), e.getBody()),
                "a voucher that already carries an attachment must be PATCHed, not POSTed again");
    }

    @Test
    void an_unrelated_400_is_not_recoverable() {
        EconomicsApiException e = rejectionFromEconomic(400,
                "{\"errorCode\":\"E04300\",\"message\":\"Validation failed. 1 error found.\"}");

        assertEquals(AttachmentRecovery.NONE,
                EconomicsService.attachmentRecoveryFor(e.getStatus(), e.getBody()));
    }

    /**
     * The markers must be read as a 400 rejection only. A 500 whose body happens to mention one
     * of them is a transport failure, and re-POSTing it would be a second upload attempt against
     * a voucher whose state is unknown.
     */
    @Test
    void the_markers_only_count_on_a_400() {
        assertEquals(AttachmentRecovery.NONE,
                EconomicsService.attachmentRecoveryFor(500, "{\"errorCode\":\"URLChanged\"}"));
        assertEquals(AttachmentRecovery.NONE,
                EconomicsService.attachmentRecoveryFor(502, "Voucher already has attachment"));
    }

    @Test
    void a_missing_body_is_not_recoverable() {
        assertEquals(AttachmentRecovery.NONE, EconomicsService.attachmentRecoveryFor(400, null));
    }

    /**
     * 404 is the one status the mapper deliberately ignores, so it still arrives as a returned
     * Response — and a returned Response never carries the 400 the recovery paths key on.
     */
    @Test
    void a_returned_response_never_triggers_a_recovery_path() {
        assertEquals(AttachmentRecovery.NONE, EconomicsService.attachmentRecoveryFor(404, null));
        assertEquals(AttachmentRecovery.NONE, EconomicsService.attachmentRecoveryFor(201, null));
    }

    /**
     * The misreport this fixes: before {@code sendFile} caught {@link EconomicsApiException}, a
     * rejection reached operators as 502 "Unexpected error during attachment upload". The status
     * and body needed to say otherwise have to survive the mapper as fields.
     */
    @Test
    void the_vendor_status_and_body_survive_for_the_error_the_operator_reads() {
        EconomicsApiException e = rejectionFromEconomic(400,
                "{\"message\":\"Attachment file type is not supported\"}");

        assertEquals(400, e.getStatus(), "not the 502 this used to be reported as");
        assertEquals("{\"message\":\"Attachment file type is not supported\"}", e.getBody());
    }
}
