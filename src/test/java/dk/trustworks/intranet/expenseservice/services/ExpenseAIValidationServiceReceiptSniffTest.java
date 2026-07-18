package dk.trustworks.intranet.expenseservice.services;

import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.util.Base64;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Covers the receipt content sniffing that guards the OpenAI vision call in
 * {@link ExpenseAIValidationService#extractExpenseData(String)}. The upload flow
 * discards the client MIME type, so PDFs and other non-image files reach the AI
 * validation path as anonymous base64 — prod incident 2026-07-17: five PDF receipts
 * were sent as {@code input_image} and each drew an HTTP 400 from OpenAI.
 */
class ExpenseAIValidationServiceReceiptSniffTest {

    private static String b64(byte[] bytes) {
        return Base64.getEncoder().encodeToString(bytes);
    }

    private static byte[] padded(byte[] prefix) {
        byte[] out = new byte[24];
        System.arraycopy(prefix, 0, out, 0, prefix.length);
        return out;
    }

    /* ---------- sniffReceiptMime ---------- */

    @Test
    void detectsJpeg() {
        assertEquals("image/jpeg", ExpenseAIValidationService.sniffReceiptMime(
                b64(padded(new byte[]{(byte) 0xFF, (byte) 0xD8, (byte) 0xFF, (byte) 0xE0}))));
    }

    @Test
    void detectsPng() {
        assertEquals("image/png", ExpenseAIValidationService.sniffReceiptMime(
                b64(padded(new byte[]{(byte) 0x89, 'P', 'N', 'G', 0x0D, 0x0A, 0x1A, 0x0A}))));
    }

    @Test
    void detectsGif() {
        assertEquals("image/gif", ExpenseAIValidationService.sniffReceiptMime(
                b64(padded("GIF89a".getBytes(StandardCharsets.US_ASCII)))));
    }

    @Test
    void detectsWebp() {
        byte[] webp = padded("RIFF????WEBP".getBytes(StandardCharsets.US_ASCII));
        assertEquals("image/webp", ExpenseAIValidationService.sniffReceiptMime(b64(webp)));
    }

    @Test
    void detectsPdf() {
        assertEquals("application/pdf", ExpenseAIValidationService.sniffReceiptMime(
                b64("%PDF-1.4\n%receipt".getBytes(StandardCharsets.US_ASCII))));
    }

    @Test
    void unknownFormatReturnsNull() {
        assertNull(ExpenseAIValidationService.sniffReceiptMime(
                b64("plain text, not an image".getBytes(StandardCharsets.US_ASCII))));
    }

    @Test
    void invalidBase64ReturnsNull() {
        assertNull(ExpenseAIValidationService.sniffReceiptMime("!!!not-base64!!!"));
    }

    @Test
    void tooShortContentReturnsNull() {
        assertNull(ExpenseAIValidationService.sniffReceiptMime(b64(new byte[]{(byte) 0xFF, (byte) 0xD8})));
    }

    @Test
    void dataUrlPrefixIsHandled() {
        String pdf = b64("%PDF-1.7 something".getBytes(StandardCharsets.US_ASCII));
        assertEquals("application/pdf",
                ExpenseAIValidationService.sniffReceiptMime("data:application/pdf;base64," + pdf));
    }

    @Test
    void whitespaceInBase64IsTolerated() {
        String jpeg = b64(padded(new byte[]{(byte) 0xFF, (byte) 0xD8, (byte) 0xFF, (byte) 0xE0}));
        String wrapped = jpeg.substring(0, 8) + "\r\n" + jpeg.substring(8);
        assertEquals("image/jpeg", ExpenseAIValidationService.sniffReceiptMime(wrapped));
    }

    /* ---------- extractExpenseData guard paths ----------
       A bare instance suffices: every guard returns before any injected
       collaborator (OpenAIService, AIConfigSnapshot) is touched. */

    @Test
    void extractSkipsPdfWithoutCallingOpenAI() {
        ExpenseAIValidationService svc = new ExpenseAIValidationService();
        String result = svc.extractExpenseData(b64("%PDF-1.4 fake receipt".getBytes(StandardCharsets.US_ASCII)));
        assertTrue(result.contains("PDF document"), "unexpected result: " + result);
    }

    @Test
    void extractSkipsUnknownFormatWithoutCallingOpenAI() {
        ExpenseAIValidationService svc = new ExpenseAIValidationService();
        String result = svc.extractExpenseData(b64("just some text bytes".getBytes(StandardCharsets.US_ASCII)));
        assertTrue(result.contains("not a readable image"), "unexpected result: " + result);
    }

    @Test
    void extractHandlesEmptyContent() {
        ExpenseAIValidationService svc = new ExpenseAIValidationService();
        assertEquals("Receipt image not provided or empty.", svc.extractExpenseData(""));
    }
}
