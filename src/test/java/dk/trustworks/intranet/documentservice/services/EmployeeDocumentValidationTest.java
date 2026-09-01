package dk.trustworks.intranet.documentservice.services;

import dk.trustworks.intranet.documentservice.model.enums.EmployeeDocumentCategory;
import dk.trustworks.intranet.documentservice.model.enums.TemplateCategory;
import dk.trustworks.intranet.documentservice.model.EmployeeDocument;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Pure unit tests for the {@link EmployeeDocumentService} validation
 * helpers (spec §11): magic-byte verification per D6 type, filename
 * sanitization (Danish characters preserved, traversal killed), the
 * ASCII S3-key slug, and the TemplateCategory→category mapping.
 */
class EmployeeDocumentValidationTest {

    // ── display-name normalization (V476) ──────────────────────────────────
    // The proposal is untrusted: it arrives from OpenAI (rename pass) or
    // from HR's PATCH field, and ends up in a Content-Disposition header.

    @Test
    void displayNameKeepsTheOriginalExtensionNotTheProposedOne() {
        assertEquals("2021_SALARY_loenregulering.pdf",
                EmployeeDocumentService.normalizeDisplayName(
                        "2021_SALARY_loenregulering.docx", "loenreg_2021_final(2).pdf"));
        assertEquals("CONTRACT_kontrakt.docx",
                EmployeeDocumentService.normalizeDisplayName("CONTRACT_kontrakt", "Kontrakt.docx"));
        assertEquals("OTHER_notat",
                EmployeeDocumentService.normalizeDisplayName("OTHER_notat.pdf", "notat"),
                "no extension on the original ⇒ none invented");
    }

    @Test
    void displayNameTraversalAndSeparatorsAreKilled() {
        String name = EmployeeDocumentService.normalizeDisplayName("../../etc/passwd", "k.pdf");
        assertFalse(name.contains("/"));
        assertFalse(name.contains(".."));
        assertEquals("etcpasswd.pdf", name);

        assertEquals("windowspath.pdf",
                EmployeeDocumentService.normalizeDisplayName("windows\\path", "k.pdf"));
    }

    @Test
    void displayNameHeaderInjectionIsStripped() {
        String name = EmployeeDocumentService.normalizeDisplayName(
                "evil\r\nSet-Cookie: a=b\"; x=\"1", "k.pdf");
        assertFalse(name.contains("\r"));
        assertFalse(name.contains("\n"));
        assertFalse(name.contains("\""));
        assertFalse(name.contains(";"));
    }

    @Test
    void displayNameBlankOrUnusableReturnsNull() {
        assertNull(EmployeeDocumentService.normalizeDisplayName(null, "k.pdf"));
        assertNull(EmployeeDocumentService.normalizeDisplayName("   ", "k.pdf"));
        assertNull(EmployeeDocumentService.normalizeDisplayName("***", "k.pdf"),
                "nothing survives the allow-list ⇒ no name proposed, not an exception");
        assertNull(EmployeeDocumentService.normalizeDisplayName("...", "k.pdf"));
    }

    @Test
    void displayNameCannotBecomeADotfile() {
        assertEquals("bashrc.pdf",
                EmployeeDocumentService.normalizeDisplayName(".bashrc", "k.pdf"));
    }

    @Test
    void displayNameTruncatesToTheColumnWidthWithoutLosingTheExtension() {
        String name = EmployeeDocumentService.normalizeDisplayName("a".repeat(400) + ".pdf", "orig.pdf");
        assertEquals(255, name.length());
        assertTrue(name.endsWith(".pdf"));
    }

    @Test
    void displayNamePreservesDanishCharacters() {
        assertEquals("2021_SALARY_lønregulering.pdf",
                EmployeeDocumentService.normalizeDisplayName(
                        "2021_SALARY_lønregulering.pdf", "loenreg.pdf"));
    }

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

    // ── delta re-copy: has the source actually changed? ────────────────────
    // Provenance is the SharePoint webUrl and an edit does not change it, so
    // this comparison is the only thing standing between a delta crawl and
    // silently keeping stale bytes.

    private static EmployeeDocument stored(String sha256, long size) {
        EmployeeDocument doc = new EmployeeDocument();
        doc.setUuid("doc-1");
        doc.setUserUuid("user-1");
        doc.setSha256(sha256);
        doc.setFileSizeBytes(size);
        return doc;
    }

    @Test
    void aHashedRowComparesByHash() {
        String sha = EmployeeDocumentService.sha256Hex("hello".getBytes(StandardCharsets.UTF_8));
        assertTrue(EmployeeDocumentService.isSameContent(stored(sha, 5), sha, 5));
    }

    @Test
    void aHashedRowWhoseSourceChangedIsNotTheSameContent() {
        String oldSha = EmployeeDocumentService.sha256Hex("hello".getBytes(StandardCharsets.UTF_8));
        String newSha = EmployeeDocumentService.sha256Hex("hello!".getBytes(StandardCharsets.UTF_8));
        assertFalse(EmployeeDocumentService.isSameContent(stored(oldSha, 5), newSha, 6));
    }

    @Test
    void aHashedRowIgnoresSizeWhenTheHashMatches() {
        // The hash is the authority; a stale recorded size must not force a
        // needless rewrite of identical bytes.
        String sha = EmployeeDocumentService.sha256Hex("hello".getBytes(StandardCharsets.UTF_8));
        assertTrue(EmployeeDocumentService.isSameContent(stored(sha, 999), sha, 5));
    }

    @Test
    void anUnhashedRowFallsBackToExactSize() {
        // Half the migrated corpus has no sha256 (server-side copies never saw
        // the bytes). Treating those as "changed" would rewrite the whole
        // corpus on the first delta run.
        assertTrue(EmployeeDocumentService.isSameContent(stored(null, 1234), "any-hash", 1234));
        assertFalse(EmployeeDocumentService.isSameContent(stored(null, 1234), "any-hash", 1235));
    }

    @Test
    void anUnhashedZeroByteRowStillComparesByItsRecordedSize() {
        // 32 production rows are zero-byte legacy re-homes; a real file
        // arriving for one of them must count as a change.
        assertTrue(EmployeeDocumentService.isSameContent(stored(null, 0), "h", 0));
        assertFalse(EmployeeDocumentService.isSameContent(stored(null, 0), "h", 4096));
    }
}
