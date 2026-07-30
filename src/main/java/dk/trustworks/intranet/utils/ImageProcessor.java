package dk.trustworks.intranet.utils;

import net.coobird.thumbnailator.Thumbnails;
import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.util.Base64;

public class ImageProcessor {

    /** A receipt prepared for the e-conomic voucher attachment endpoint. */
    public record ReceiptAttachment(byte[] bytes, String mediaType, String filename) {}

    /**
     * Prepares a stored receipt (base64, optionally data-URL-prefixed) for upload to e-conomic.
     * <ul>
     *   <li>PDF receipts pass through unmodified — e-conomic accepts PDF attachments, and
     *       rendering them to JPEG here would only lose fidelity.</li>
     *   <li>Raster images decodable by ImageIO (JPEG/PNG/GIF/BMP/TIFF) are compressed to JPEG
     *       under the attachment size limit.</li>
     *   <li>Anything else — HEIC/HEIF (no JVM decoder, and e-conomic rejects the format) or
     *       corrupt data — fails with {@link UndecodableReceiptException} so the caller can park
     *       the expense as an actionable failure instead of silently dropping it.</li>
     * </ul>
     */
    public static ReceiptAttachment prepareReceiptForUpload(String base64Receipt) throws IOException {
        byte[] receiptBytes = decodeBase64Receipt(base64Receipt);

        if (isPdf(receiptBytes)) {
            return new ReceiptAttachment(receiptBytes, "application/pdf", "receipt.pdf");
        }

        BufferedImage image;
        try {
            image = ImageIO.read(new ByteArrayInputStream(receiptBytes));
        } catch (IOException e) {
            // Recognized but corrupt/truncated image data can make ImageIO throw instead of
            // returning null. Keep that decoder failure on the same item-level failure path.
            throw new UndecodableReceiptException(e);
        }

        // ImageIO.read() returns null when the bytes are not a recognized image. HEIC/HEIF gets
        // its own message — it is a real photo the employee can re-export, not a corrupt file.
        if (image == null) {
            if (isHeif(receiptBytes)) {
                throw new UndecodableReceiptException(UndecodableReceiptException.HEIC_MESSAGE);
            }
            throw new UndecodableReceiptException();
        }

        return new ReceiptAttachment(compressToJpeg(image), "image/jpeg", "receipt.jpg");
    }

    /** Decodes the stored receipt, tolerating an optional {@code data:<mime>;base64,} prefix. */
    private static byte[] decodeBase64Receipt(String base64Receipt) throws UndecodableReceiptException {
        if (base64Receipt == null || base64Receipt.isBlank()) {
            throw new UndecodableReceiptException();
        }
        String payload = base64Receipt;
        if (payload.startsWith("data:")) {
            int comma = payload.indexOf(',');
            if (comma < 0) {
                throw new UndecodableReceiptException();
            }
            payload = payload.substring(comma + 1);
        }
        try {
            // MIME decoder: same alphabet as the basic decoder but ignores embedded line breaks.
            return Base64.getMimeDecoder().decode(payload);
        } catch (IllegalArgumentException e) {
            throw new UndecodableReceiptException(e);
        }
    }

    private static boolean isPdf(byte[] bytes) {
        return bytes.length >= 4
                && bytes[0] == '%' && bytes[1] == 'P' && bytes[2] == 'D' && bytes[3] == 'F';
    }

    /** ISO-BMFF container ("ftyp" box at offset 4) — HEIC/HEIF/AVIF and friends. */
    private static boolean isHeif(byte[] bytes) {
        return bytes.length >= 12
                && bytes[4] == 'f' && bytes[5] == 't' && bytes[6] == 'y' && bytes[7] == 'p';
    }

    // An encode failure here is an infra problem, not a bad receipt — the image already decoded.
    // Let the IOException propagate as a regular item failure rather than "undecodable".
    private static byte[] compressToJpeg(BufferedImage image) throws IOException {
        ByteArrayOutputStream baos = new ByteArrayOutputStream();

        // Start with a high-quality image and adjust if necessary
        double quality = 0.9;
        long maxSize = 8 * 1024 * 1024; // 8 MB in bytes

        // Compress and potentially resize the image until it is under the max size
        // This loop decreases the quality in steps if necessary
        while (true) {
            baos.reset(); // Clear the previous output
            Thumbnails.of(image)
                    .scale(1) // Start with original size
                    .outputFormat("jpg")
                    .outputQuality(quality)
                    .toOutputStream(baos);

            if (baos.size() < maxSize || quality <= 0.1) {
                // Stop if the size is under the limit or quality is too low
                break;
            }

            // Decrease quality to reduce size
            quality -= 0.1;
        }

        return baos.toByteArray();
    }
}
