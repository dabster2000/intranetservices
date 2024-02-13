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
        BufferedImage image = ImageIO.read(bais);

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