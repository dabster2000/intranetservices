package dk.trustworks.intranet.aggregates.crm.enrichment.services;

import org.junit.jupiter.api.Test;

import javax.imageio.ImageIO;
import java.awt.Color;
import java.awt.Graphics2D;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.net.InetAddress;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The URL a language model proposed is attacker-controlled input. These pin the rules that
 * keep the nightly logo job from being a server-side request forgery primitive, and the
 * image rules that keep a non-image out of the files table.
 */
class SafeImageDownloaderTest {

    private static InetAddress ip(String s) throws Exception {
        return InetAddress.getByName(s);
    }

    @Test
    void privateAndSpecialAddressesAreNotPublic() throws Exception {
        assertFalse(SafeImageDownloader.isPublicUnicast(ip("127.0.0.1")));
        assertFalse(SafeImageDownloader.isPublicUnicast(ip("10.1.2.3")));
        assertFalse(SafeImageDownloader.isPublicUnicast(ip("172.16.5.5")));
        assertFalse(SafeImageDownloader.isPublicUnicast(ip("192.168.1.1")));
        assertFalse(SafeImageDownloader.isPublicUnicast(ip("169.254.169.254")), "cloud metadata");
        assertFalse(SafeImageDownloader.isPublicUnicast(ip("100.64.0.1")), "CGNAT");
        assertFalse(SafeImageDownloader.isPublicUnicast(ip("0.0.0.0")));
        assertFalse(SafeImageDownloader.isPublicUnicast(ip("224.0.0.1")), "multicast");
        assertFalse(SafeImageDownloader.isPublicUnicast(ip("255.255.255.255")));
        assertFalse(SafeImageDownloader.isPublicUnicast(ip("::1")));
        assertFalse(SafeImageDownloader.isPublicUnicast(ip("fd12::1")), "ULA");
        assertFalse(SafeImageDownloader.isPublicUnicast(ip("fe80::1")), "link-local v6");
        assertFalse(SafeImageDownloader.isPublicUnicast(InetAddress.getByAddress(new byte[]{
                0, 0, 0, 0, 0, 0, 0, 0, 0, 0, (byte) 0xff, (byte) 0xff, 10, 0, 0, 1})), "IPv4-mapped private");
    }

    @Test
    void publicAddressesArePublic() throws Exception {
        assertTrue(SafeImageDownloader.isPublicUnicast(ip("8.8.8.8")));
        assertTrue(SafeImageDownloader.isPublicUnicast(ip("185.60.216.35")));
        assertTrue(SafeImageDownloader.isPublicUnicast(ip("2606:4700::1111")));
    }

    @Test
    void urlRuleRefusesWhatItMust() {
        assertThrows(SafeImageDownloader.DownloadRejected.class, () -> SafeImageDownloader.validateUrl(null));
        assertThrows(SafeImageDownloader.DownloadRejected.class, () -> SafeImageDownloader.validateUrl("http://example.com/logo.png"));
        assertThrows(SafeImageDownloader.DownloadRejected.class, () -> SafeImageDownloader.validateUrl("ftp://example.com/logo.png"));
        assertThrows(SafeImageDownloader.DownloadRejected.class, () -> SafeImageDownloader.validateUrl("https://user:pw@example.com/logo.png"));
        assertThrows(SafeImageDownloader.DownloadRejected.class, () -> SafeImageDownloader.validateUrl("https://localhost/logo.png"));
        assertThrows(SafeImageDownloader.DownloadRejected.class, () -> SafeImageDownloader.validateUrl("https://intra.internal/logo.png"));
        assertThrows(SafeImageDownloader.DownloadRejected.class, () -> SafeImageDownloader.validateUrl("https://127.0.0.1/logo.png"));
        assertThrows(SafeImageDownloader.DownloadRejected.class, () -> SafeImageDownloader.validateUrl("https://169.254.169.254/latest/meta-data"));
        assertThrows(SafeImageDownloader.DownloadRejected.class, () -> SafeImageDownloader.validateUrl("https://[::1]/logo.png"));
        assertThrows(SafeImageDownloader.DownloadRejected.class, () -> SafeImageDownloader.validateUrl("data:image/png;base64,AAAA"));
        assertThrows(SafeImageDownloader.DownloadRejected.class, () -> SafeImageDownloader.validateUrl("https:///logo.png"));
    }

    @Test
    void imageRuleRefusesNonImagesAndTinyImages() throws Exception {
        assertThrows(SafeImageDownloader.DownloadRejected.class, () -> SafeImageDownloader.validateImage(new byte[0]));
        assertThrows(SafeImageDownloader.DownloadRejected.class,
                () -> SafeImageDownloader.validateImage("<html><body>nope</body></html>".getBytes()));
        assertThrows(SafeImageDownloader.DownloadRejected.class,
                () -> SafeImageDownloader.validateImage("<svg xmlns='http://www.w3.org/2000/svg'/>".getBytes()));
        assertThrows(SafeImageDownloader.DownloadRejected.class, () -> SafeImageDownloader.validateImage(png(8, 8, true)));
        SafeImageDownloader.Downloaded ok = SafeImageDownloader.validateImage(png(300, 120, false));
        assertEquals(300, ok.width());
        assertEquals(120, ok.height());
        assertEquals("image/png", ok.mimeType());
    }

    @Test
    void transparencyIsFlattenedOntoWhite() throws Exception {
        byte[] flat = SafeImageDownloader.flattenToWhite(png(120, 60, true));
        BufferedImage image = ImageIO.read(new ByteArrayInputStream(flat));
        assertFalse(image.getColorModel().hasAlpha(), "the stored PNG must carry no alpha channel");
        // The corner was fully transparent in the source and must now be white, not black.
        assertEquals(0xFFFFFF, image.getRGB(0, 0) & 0xFFFFFF);
        // The opaque red square in the middle survives.
        assertEquals(0xFF0000, image.getRGB(60, 30) & 0xFFFFFF);
    }

    private static byte[] png(int width, int height, boolean transparent) throws Exception {
        BufferedImage image = new BufferedImage(width, height,
                transparent ? BufferedImage.TYPE_INT_ARGB : BufferedImage.TYPE_INT_RGB);
        Graphics2D g = image.createGraphics();
        try {
            if (!transparent) {
                g.setColor(Color.WHITE);
                g.fillRect(0, 0, width, height);
            }
            g.setColor(Color.RED);
            g.fillRect(width / 4, height / 4, width / 2, height / 2);
        } finally {
            g.dispose();
        }
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        ImageIO.write(image, "png", out);
        return out.toByteArray();
    }
}
