package dk.trustworks.intranet.utils;

import org.junit.jupiter.api.Test;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.Base64;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Guards the e-conomic receipt upload path (EconomicsService.sendFile -> ExpenseService).
 *
 * ImageIO.read() returns null for unrecognized formats such as PDF/HEIC and can throw for
 * recognized but corrupt or truncated image data. Before the decoder guard in ImageProcessor,
 * a null result flowed into Thumbnailator and surfaced on the expense as a cryptic
 * NullPointerException("Image cannot be null."). The guard emits a typed signal so the batch
 * writer can park the expense for attention, log a warning, and continue the chunk.
 */
class ImageProcessorTest {

    /** Non-image content (a PDF here) must fail with the item-level decoder signal, not a cryptic NPE. */
    @Test
    void nonDecodableReceiptThrowsTypedException() {
        // Valid bytes, but ImageIO cannot decode them into a BufferedImage (returns null).
        byte[] pdfBytes = "%PDF-1.7 fake pdf receipt, not a real image".getBytes(StandardCharsets.US_ASCII);
        String base64Pdf = Base64.getEncoder().encodeToString(pdfBytes);

        UndecodableReceiptException ex = assertThrows(UndecodableReceiptException.class,
                () -> ImageProcessor.convertBase64ToImageAndCompress(base64Pdf));
        assertEquals(UndecodableReceiptException.DEFAULT_MESSAGE, ex.getMessage());
    }

    /** Recognized but truncated image data can make ImageIO throw rather than return null. */
    @Test
    void truncatedRecognizedImageThrowsTypedException() {
        byte[] truncatedPng = new byte[]{
                (byte) 0x89, 'P', 'N', 'G', 0x0D, 0x0A, 0x1A, 0x0A
        };
        String base64Png = Base64.getEncoder().encodeToString(truncatedPng);

        UndecodableReceiptException ex = assertThrows(UndecodableReceiptException.class,
                () -> ImageProcessor.convertBase64ToImageAndCompress(base64Png));
        assertEquals(UndecodableReceiptException.DEFAULT_MESSAGE, ex.getMessage());
    }

    /** A genuine image still compresses to a non-empty JPEG (happy path unchanged by the guard). */
    @Test
    void decodableImageIsCompressed() throws Exception {
        BufferedImage img = new BufferedImage(64, 64, BufferedImage.TYPE_INT_RGB);
        for (int x = 0; x < 64; x++) {
            for (int y = 0; y < 64; y++) {
                img.setRGB(x, y, ((x * 4) << 16) | ((y * 4) << 8));
            }
        }
        ByteArrayOutputStream png = new ByteArrayOutputStream();
        ImageIO.write(img, "png", png);
        String base64Png = Base64.getEncoder().encodeToString(png.toByteArray());

        byte[] jpg = ImageProcessor.convertBase64ToImageAndCompress(base64Png);
        assertNotNull(jpg);
        assertTrue(jpg.length > 0, "compressed jpg should be non-empty");
    }
}
