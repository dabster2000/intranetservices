package dk.trustworks.intranet.utils;

import dk.trustworks.intranet.utils.ImageProcessor.ReceiptAttachment;
import org.junit.jupiter.api.Test;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.Base64;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Guards the e-conomic receipt upload path (EconomicsService.sendFile -> ExpenseService).
 *
 * The nightly upload used to raster-decode every receipt via ImageIO, which cannot read PDF or
 * HEIC — those expenses were parked and never reached accounting (55% of a prod run on
 * 2026-07-30). The rules now are: PDFs pass through unmodified (e-conomic accepts them),
 * decodable images compress to JPEG, and only HEIC/corrupt content fails — with an actionable
 * message — via UndecodableReceiptException.
 */
class ImageProcessorTest {

    /** PDF receipts must reach e-conomic as-is: original bytes, application/pdf, .pdf filename. */
    @Test
    void pdfReceiptPassesThroughUnmodified() throws Exception {
        byte[] pdfBytes = "%PDF-1.7 fake pdf receipt, not a real image".getBytes(StandardCharsets.US_ASCII);
        String base64Pdf = Base64.getEncoder().encodeToString(pdfBytes);

        ReceiptAttachment attachment = ImageProcessor.prepareReceiptForUpload(base64Pdf);

        assertArrayEquals(pdfBytes, attachment.bytes(), "PDF bytes must not be re-encoded");
        assertEquals("application/pdf", attachment.mediaType());
        assertEquals("receipt.pdf", attachment.filename());
    }

    /** A data-URL prefix (as stored by some upload clients) must not break PDF detection. */
    @Test
    void dataUrlPrefixedPdfIsAccepted() throws Exception {
        byte[] pdfBytes = "%PDF-1.4 prefixed pdf receipt".getBytes(StandardCharsets.US_ASCII);
        String dataUrl = "data:application/pdf;base64," + Base64.getEncoder().encodeToString(pdfBytes);

        ReceiptAttachment attachment = ImageProcessor.prepareReceiptForUpload(dataUrl);

        assertArrayEquals(pdfBytes, attachment.bytes());
        assertEquals("application/pdf", attachment.mediaType());
    }

    /** A genuine image still compresses to a non-empty JPEG attachment. */
    @Test
    void decodableImageIsCompressedToJpeg() throws Exception {
        BufferedImage img = new BufferedImage(64, 64, BufferedImage.TYPE_INT_RGB);
        for (int x = 0; x < 64; x++) {
            for (int y = 0; y < 64; y++) {
                img.setRGB(x, y, ((x * 4) << 16) | ((y * 4) << 8));
            }
        }
        ByteArrayOutputStream png = new ByteArrayOutputStream();
        ImageIO.write(img, "png", png);
        String base64Png = Base64.getEncoder().encodeToString(png.toByteArray());

        ReceiptAttachment attachment = ImageProcessor.prepareReceiptForUpload(base64Png);

        assertNotNull(attachment.bytes());
        assertTrue(attachment.bytes().length > 0, "compressed jpg should be non-empty");
        assertEquals("image/jpeg", attachment.mediaType());
        assertEquals("receipt.jpg", attachment.filename());
        // JPEG magic bytes prove the payload was actually transcoded
        assertEquals((byte) 0xFF, attachment.bytes()[0]);
        assertEquals((byte) 0xD8, attachment.bytes()[1]);
    }

    /** HEIC/HEIF (ISO-BMFF "ftyp" container) fails with a message naming the format. */
    @Test
    void heicReceiptFailsWithActionableMessage() {
        byte[] heicBytes = new byte[32];
        // size box (0x18) + "ftypheic" — the standard iPhone HEIC file header
        heicBytes[3] = 0x18;
        heicBytes[4] = 'f'; heicBytes[5] = 't'; heicBytes[6] = 'y'; heicBytes[7] = 'p';
        heicBytes[8] = 'h'; heicBytes[9] = 'e'; heicBytes[10] = 'i'; heicBytes[11] = 'c';
        String base64Heic = Base64.getEncoder().encodeToString(heicBytes);

        UndecodableReceiptException ex = assertThrows(UndecodableReceiptException.class,
                () -> ImageProcessor.prepareReceiptForUpload(base64Heic));
        assertEquals(UndecodableReceiptException.HEIC_MESSAGE, ex.getMessage());
    }

    /** Recognized but truncated image data can make ImageIO throw rather than return null. */
    @Test
    void truncatedRecognizedImageThrowsTypedException() {
        byte[] truncatedPng = new byte[]{
                (byte) 0x89, 'P', 'N', 'G', 0x0D, 0x0A, 0x1A, 0x0A
        };
        String base64Png = Base64.getEncoder().encodeToString(truncatedPng);

        UndecodableReceiptException ex = assertThrows(UndecodableReceiptException.class,
                () -> ImageProcessor.prepareReceiptForUpload(base64Png));
        assertEquals(UndecodableReceiptException.DEFAULT_MESSAGE, ex.getMessage());
    }

    /** Unrecognized binary garbage fails with the corrupt-file message. */
    @Test
    void unrecognizedContentThrowsTypedException() {
        byte[] garbage = "this is not an image, a pdf, or anything else".getBytes(StandardCharsets.US_ASCII);
        String base64Garbage = Base64.getEncoder().encodeToString(garbage);

        UndecodableReceiptException ex = assertThrows(UndecodableReceiptException.class,
                () -> ImageProcessor.prepareReceiptForUpload(base64Garbage));
        assertEquals(UndecodableReceiptException.DEFAULT_MESSAGE, ex.getMessage());
    }

    /** Content that is not valid base64 at all is a corrupt receipt, not a crash. */
    @Test
    void invalidBase64ThrowsTypedException() {
        assertThrows(UndecodableReceiptException.class,
                () -> ImageProcessor.prepareReceiptForUpload("!!!not-base64!!!"));
        assertThrows(UndecodableReceiptException.class,
                () -> ImageProcessor.prepareReceiptForUpload(""));
    }
}
