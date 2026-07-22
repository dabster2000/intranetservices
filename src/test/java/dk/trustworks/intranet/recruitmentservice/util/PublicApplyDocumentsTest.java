package dk.trustworks.intranet.recruitmentservice.util;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The P5 public-form file guard: PDF/JPEG/PNG magic bytes must match the
 * asserted MIME type — a public caller lying about Content-Type never
 * gets past the pairing. Plain unit test, no framework.
 */
class PublicApplyDocumentsTest {

    private static final byte[] PDF = {0x25, 0x50, 0x44, 0x46, 0x2d, 0x31, 0x2e, 0x37};
    private static final byte[] JPEG = {(byte) 0xff, (byte) 0xd8, (byte) 0xff, (byte) 0xe0, 0x00};
    private static final byte[] PNG = {
            (byte) 0x89, 0x50, 0x4e, 0x47, 0x0d, 0x0a, 0x1a, 0x0a, 0x00};
    private static final byte[] ZIP = {'P', 'K', 0x03, 0x04, 0x14};

    // ---- Matching formats -----------------------------------------------------

    @Test
    void matchingMagicBytes_pass() {
        assertTrue(PublicApplyDocuments.magicMatches("application/pdf", PDF));
        assertTrue(PublicApplyDocuments.magicMatches("image/jpeg", JPEG));
        assertTrue(PublicApplyDocuments.magicMatches("image/png", PNG));
    }

    // ---- Spoofed content types ------------------------------------------------

    @Test
    void pdfBytesWithImageMime_rejected() {
        assertFalse(PublicApplyDocuments.magicMatches("image/jpeg", PDF));
        assertFalse(PublicApplyDocuments.magicMatches("image/png", PDF));
    }

    @Test
    void imageBytesWithPdfMime_rejected() {
        assertFalse(PublicApplyDocuments.magicMatches("application/pdf", JPEG));
        assertFalse(PublicApplyDocuments.magicMatches("application/pdf", PNG));
    }

    @Test
    void unlistedMime_rejectedEvenWithArbitraryBytes() {
        assertFalse(PublicApplyDocuments.magicMatches("application/zip", ZIP));
        assertFalse(PublicApplyDocuments.magicMatches("application/octet-stream", PDF));
    }

    // ---- Degenerate input -----------------------------------------------------

    @Test
    void nullOrShortInput_rejected() {
        assertFalse(PublicApplyDocuments.magicMatches(null, PDF));
        assertFalse(PublicApplyDocuments.magicMatches("application/pdf", null));
        assertFalse(PublicApplyDocuments.magicMatches("application/pdf", new byte[]{0x25, 0x50}));
        // PNG needs 8 bytes — a 4-byte prefix must not pass.
        assertFalse(PublicApplyDocuments.magicMatches("image/png",
                new byte[]{(byte) 0x89, 0x50, 0x4e, 0x47}));
    }

    // ---- Content-type normalisation -------------------------------------------

    @Test
    void normaliseContentType_stripsCharsetAndLowercases() {
        assertEquals("application/pdf",
                PublicApplyDocuments.normaliseContentType("Application/PDF; charset=UTF-8"));
        assertEquals("image/jpeg", PublicApplyDocuments.normaliseContentType(" image/JPEG "));
        assertEquals("", PublicApplyDocuments.normaliseContentType(null));
    }

    // ---- Filename sanitiser ---------------------------------------------------

    @Test
    void sanitiseFilename_allowlistsAndCollapsesDots() {
        assertEquals("cv.pdf", PublicApplyDocuments.sanitiseFilename("cv.pdf"));
        assertEquals("my cv-2.pdf", PublicApplyDocuments.sanitiseFilename("my cv-2.pdf"));
        assertEquals(".etcpasswd", PublicApplyDocuments.sanitiseFilename("../../etc/passwd"),
                "path separators are stripped and dot runs collapse — no traversal survives");
        assertEquals("evil.pdf", PublicApplyDocuments.sanitiseFilename("evil....pdf"));
        assertEquals("Ansgning.pdf", PublicApplyDocuments.sanitiseFilename("Ansøgning.pdf"));
        assertEquals("", PublicApplyDocuments.sanitiseFilename("///"));
        assertEquals("", PublicApplyDocuments.sanitiseFilename(null));
    }
}
