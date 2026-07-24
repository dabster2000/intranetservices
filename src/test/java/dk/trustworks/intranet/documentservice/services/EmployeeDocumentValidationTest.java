package dk.trustworks.intranet.documentservice.services;

import dk.trustworks.intranet.documentservice.model.enums.EmployeeDocumentCategory;
import dk.trustworks.intranet.documentservice.model.enums.TemplateCategory;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Pure unit tests for the {@link EmployeeDocumentService} validation
 * helpers (spec §11): magic-byte verification per D6 type, filename
 * sanitization (Danish characters preserved, traversal killed), the
 * ASCII S3-key slug, and the TemplateCategory→category mapping.
 */
class EmployeeDocumentValidationTest {

    // ── magic bytes ────────────────────────────────────────────────────────

    @Test
    void pdfMagic() {
        assertTrue(EmployeeDocumentService.magicMatches("application/pdf",
                "%PDF-1.7 rest".getBytes(StandardCharsets.US_ASCII)));
        assertFalse(EmployeeDocumentService.magicMatches("application/pdf",
                new byte[]{0x50, 0x4b, 0x03, 0x04}));
    }

    @Test
    void jpegMagic() {
        assertTrue(EmployeeDocumentService.magicMatches("image/jpeg",
                new byte[]{(byte) 0xff, (byte) 0xd8, (byte) 0xff, (byte) 0xe0}));
        assertFalse(EmployeeDocumentService.magicMatches("image/jpeg",
                "%PDF".getBytes(StandardCharsets.US_ASCII)));
    }

    @Test
    void pngMagic() {
        assertTrue(EmployeeDocumentService.magicMatches("image/png",
                new byte[]{(byte) 0x89, 0x50, 0x4e, 0x47, 0x0d, 0x0a, 0x1a, 0x0a, 0x00}));
        assertFalse(EmployeeDocumentService.magicMatches("image/png",
                new byte[]{(byte) 0x89, 0x50, 0x4e, 0x47}));
    }

    @Test
    void officeZipMagic() {
        byte[] zip = new byte[]{0x50, 0x4b, 0x03, 0x04, 0x00};
        assertTrue(EmployeeDocumentService.magicMatches(
                "application/vnd.openxmlformats-officedocument.wordprocessingml.document", zip));
        assertTrue(EmployeeDocumentService.magicMatches(
                "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet", zip));
        // A polyglot claiming docx but carrying a PDF is refused.
        assertFalse(EmployeeDocumentService.magicMatches(
                "application/vnd.openxmlformats-officedocument.wordprocessingml.document",
                "%PDF".getBytes(StandardCharsets.US_ASCII)));
    }

    @Test
    void msgIsOle2NotPrintable() {
        // Implementation finding: .msg is a binary OLE2 compound file —
        // the spec's "lenient printable header" applies to .eml only.
        assertTrue(EmployeeDocumentService.magicMatches("application/vnd.ms-outlook",
                new byte[]{(byte) 0xd0, (byte) 0xcf, 0x11, (byte) 0xe0, (byte) 0xa1}));
        assertFalse(EmployeeDocumentService.magicMatches("application/vnd.ms-outlook",
                "From: someone".getBytes(StandardCharsets.US_ASCII)));
    }

    @Test
    void emlLenientHeaderCheck() {
        assertTrue(EmployeeDocumentService.magicMatches("message/rfc822",
                "From: hr@trustworks.dk\r\nSubject: Kontrakt\r\n".getBytes(StandardCharsets.US_ASCII)));
        // Binary bytes → not an eml.
        assertFalse(EmployeeDocumentService.magicMatches("message/rfc822",
                new byte[]{(byte) 0xd0, (byte) 0xcf, 0x11, (byte) 0xe0}));
        // No header colon → not an eml.
        assertFalse(EmployeeDocumentService.magicMatches("message/rfc822",
                "just some text without a header".getBytes(StandardCharsets.US_ASCII)));
    }

    @Test
    void unknownTypeNeverMatches() {
        assertFalse(EmployeeDocumentService.magicMatches("application/octet-stream",
                "%PDF".getBytes(StandardCharsets.US_ASCII)));
    }

    // ── filename sanitization ──────────────────────────────────────────────

    @Test
    void sanitizeKeepsDanishCharactersAndNormalPunctuation() {
        assertEquals("Ansættelseskontrakt (underskrevet).pdf",
                EmployeeDocumentService.sanitizeFilename("Ansættelseskontrakt (underskrevet).pdf"));
    }

    @Test
    void sanitizeKillsTraversalAndSpecials() {
        // Slashes are dropped, dot runs collapse — '..' can never resurface.
        assertEquals(".etcpasswd", EmployeeDocumentService.sanitizeFilename("../../etc/passwd"));
        assertEquals("con .pdf", EmployeeDocumentService.sanitizeFilename("con .pdf"));
        assertEquals("a.pdf", EmployeeDocumentService.sanitizeFilename("a....pdf"));
    }

    @Test
    void sanitizeEmptyResult() {
        assertEquals("", EmployeeDocumentService.sanitizeFilename("////"));
        assertEquals("", EmployeeDocumentService.sanitizeFilename(null));
    }

    // ── S3 key slug ────────────────────────────────────────────────────────

    @Test
    void slugTransliteratesDanishAndLowercases() {
        assertEquals("ansaettelseskontrakt-underskrevet.pdf",
                EmployeeDocumentService.asciiSlug("Ansættelseskontrakt (underskrevet).pdf", 80));
    }

    @Test
    void slugTruncatesAndNeverEmpty() {
        assertEquals(10, EmployeeDocumentService.asciiSlug("abcdefghijklmnop.pdf", 10).length());
        assertEquals("file", EmployeeDocumentService.asciiSlug("øæå".repeat(0), 80));
    }

    @Test
    void buildKeyShape() {
        String key = EmployeeDocumentService.buildKey(
                "11111111-2222-3333-4444-555555555555", "doc-uuid", "Kontrakt.pdf");
        assertEquals("users/11111111-2222-3333-4444-555555555555/doc-uuid-kontrakt.pdf", key);
    }

    // ── content type inference (promotion path) ────────────────────────────

    @Test
    void contentTypeFromFilename() {
        assertEquals("application/pdf", EmployeeDocumentService.contentTypeFromFilename("a.PDF"));
        assertEquals("image/jpeg", EmployeeDocumentService.contentTypeFromFilename("scan.jpeg"));
        assertEquals("message/rfc822", EmployeeDocumentService.contentTypeFromFilename("mail.eml"));
        assertEquals("application/octet-stream", EmployeeDocumentService.contentTypeFromFilename("odd.bin"));
    }

    // ── template category mapping (spec §6.5.1) ────────────────────────────

    @Test
    void templateCategoryMapping() {
        assertEquals(EmployeeDocumentCategory.CONTRACT,
                EmployeeDocumentCategory.fromTemplateCategory(TemplateCategory.EMPLOYMENT));
        assertEquals(EmployeeDocumentCategory.CONTRACT,
                EmployeeDocumentCategory.fromTemplateCategory(TemplateCategory.CONSULTANT));
        assertEquals(EmployeeDocumentCategory.ADDENDUM,
                EmployeeDocumentCategory.fromTemplateCategory(TemplateCategory.AMENDMENT));
        assertEquals(EmployeeDocumentCategory.DECLARATION,
                EmployeeDocumentCategory.fromTemplateCategory(TemplateCategory.NDA));
        assertEquals(EmployeeDocumentCategory.VACATION,
                EmployeeDocumentCategory.fromTemplateCategory(TemplateCategory.VACATION));
        assertEquals(EmployeeDocumentCategory.OTHER,
                EmployeeDocumentCategory.fromTemplateCategory(null));
    }

    // ── misc helpers ───────────────────────────────────────────────────────

    @Test
    void normalizeContentTypeStripsCharset() {
        assertEquals("application/pdf",
                EmployeeDocumentService.normalizeContentType("Application/PDF; charset=utf-8"));
        assertEquals("", EmployeeDocumentService.normalizeContentType(null));
    }

    @Test
    void sha256IsStableHex() {
        assertEquals("2cf24dba5fb0a30e26e83b2ac5b9e29e1b161e5c1fa7425e73043362938b9824",
                EmployeeDocumentService.sha256Hex("hello".getBytes(StandardCharsets.UTF_8)));
    }

    @Test
    void trimToRespectsColumnWidth() {
        assertEquals("abc", EmployeeDocumentService.trimTo("abc", 10));
        assertEquals("ab", EmployeeDocumentService.trimTo("abc", 2));
        assertEquals(null, EmployeeDocumentService.trimTo(null, 2));
    }
}
