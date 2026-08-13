package dk.trustworks.intranet.expenseservice.exceptions;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The e-conomic HTTP status and response body must be part of getMessage():
 * stack-trace logging and batch exception tracking only ever surface the
 * message, and a bare "Failed to post voucher to e-conomics" made the
 * 2026-08-12 e-conomic 503 outage undiagnosable from the logs.
 */
class ExpenseUploadExceptionTest {

    @Test
    void message_carries_http_status_and_response_details() {
        ExpenseUploadException ex = new ExpenseUploadException(
                "Failed to post voucher to e-conomics", null, 503,
                "upstream connect error or disconnect/reset before headers");

        assertTrue(ex.getMessage().contains("Failed to post voucher to e-conomics"));
        assertTrue(ex.getMessage().contains("HTTP 503"));
        assertTrue(ex.getMessage().contains("upstream connect error"));
        assertEquals(503, ex.getHttpStatus());
        assertEquals("upstream connect error or disconnect/reset before headers", ex.getErrorDetails());
    }

    @Test
    void message_without_status_or_details_stays_plain() {
        ExpenseUploadException ex = new ExpenseUploadException("Attachment too large", null, null, null);
        assertEquals("Attachment too large", ex.getMessage());
    }

    @Test
    void long_response_bodies_are_truncated_in_message_but_kept_in_errorDetails() {
        String body = "x".repeat(2000);
        ExpenseUploadException ex = new ExpenseUploadException("Voucher not posted", null, 400, body);

        assertTrue(ex.getMessage().contains("..."));
        assertTrue(ex.getMessage().length() < 700);
        assertEquals(body, ex.getErrorDetails());
    }

    @Test
    void detailed_message_includes_message_and_cause() {
        ExpenseUploadException ex = new ExpenseUploadException(
                "Failed to post voucher to e-conomics", new RuntimeException("connection reset"), 502, "bad gateway");

        String detailed = ex.getDetailedMessage();
        assertTrue(detailed.contains("HTTP 502"));
        assertTrue(detailed.contains("bad gateway"));
        assertTrue(detailed.contains("connection reset"));
        assertFalse(detailed.isBlank());
    }
}
