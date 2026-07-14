package dk.trustworks.intranet.utils;

import net.coobird.thumbnailator.Thumbnails;
import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.util.Base64;

public class ImageProcessor {

    public static byte[] convertBase64ToImageAndCompress(String base64Image) throws IOException {
        // Decode Base64 to byte array
        byte[] imageBytes = Base64.getDecoder().decode(base64Image);

        // Convert byte array to BufferedImage
        ByteArrayInputStream bais = new ByteArrayInputStream(imageBytes);
        BufferedImage image;
        try {
            image = ImageIO.read(bais);
        } catch (IOException e) {
            // Recognized but corrupt/truncated image data can make ImageIO throw instead of
            // returning null. Keep that decoder failure on the same item-level skip path.
            throw new UndecodableReceiptException(e);
        }

        // ImageIO.read() returns null when the bytes are not a recognized image — e.g. a PDF or
        // HEIC receipt. Passing that null straight into
        // Thumbnailator below throws a cryptic NullPointerException("Image cannot be null."),
        // which then surfaces on the expense as an opaque "Unexpected error: NullPointerException".
        // Fail with a dedicated exception so ExpenseItemWriter can park the expense for attention,
        // log a warning, and continue the chunk without classifying the receipt as a batch failure.
        if (image == null) {
            throw new UndecodableReceiptException();
        }

        // Use Thumbnailator to compress and resize the image
        ByteArrayOutputStream baos = new ByteArrayOutputStream();

        // Start with a high-quality image and adjust if necessary
        double quality = 0.9;
        long maxSize = 8 * 1024 * 1024; // 8 MB in bytes

        // Compress and potentially resize the image until it is under the max size
        // This loop decreases the quality in steps if necessary
        while(true) {
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
