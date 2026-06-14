package dk.trustworks.intranet.utils;

import org.junit.jupiter.api.Test;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.Base64;

import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Guards the e-conomic receipt upload path (EconomicsService.sendFile -> ExpenseService).
 *
 * ImageIO.read() RETURNS null (it does not throw) for content it cannot decode — e.g. a PDF,
 * HEIC, or a corrupt receipt. Before the null guard in ImageProcessor, that null flowed straight
 * into Thumbnailator and surfaced on the expense as a cryptic
 * NullPointerException("Image cannot be null."), seen once on prod (2026-06-13, expense
 * 78c1d153). The guard turns it into an actionable IOException so the single expense is failed
 * (with its uuid already logged) and the chunk continues.
 */
class ImageProcessorTest {

    /** Non-image content (a PDF here) must fail with an actionable IOException, not a cryptic NPE. */
    @Test
    void nonDecodableReceiptThrowsActionableIOException() {
        // Valid bytes, but ImageIO cannot decode them into a BufferedImage (returns null).
        byte[] pdfBytes = "%PDF-1.7 fake pdf receipt, not a real image".getBytes(StandardCharsets.US_ASCII);
        String base64Pdf = Base64.getEncoder().encodeToString(pdfBytes);

        IOException ex = assertThrows(IOException.class,
                () -> ImageProcessor.convertBase64ToImageAndCompress(base64Pdf));
        assertTrue(ex.getMessage() != null && ex.getMessage().toLowerCase().contains("not a decodable image"),
                "message should identify the non-image receipt, was: " + ex.getMessage());
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
