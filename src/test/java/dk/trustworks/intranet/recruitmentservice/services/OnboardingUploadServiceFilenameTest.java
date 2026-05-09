package dk.trustworks.intranet.recruitmentservice.services;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Unit tests for the package-private filename sanitiser used by the public
 * onboarding upload endpoint. The endpoint is {@code @PermitAll} - the
 * filename is therefore untrusted input and must be stripped of path
 * separators, control characters, and {@code ..} traversal sequences
 * before it can be embedded in an audit row, used as a SharePoint path
 * leaf, or written to logs.
 */
class OnboardingUploadServiceFilenameTest {

    @Test
    void sanitise_null_returnsEmpty() {
        assertEquals("", OnboardingUploadService.sanitiseFilename(null));
    }

    @Test
    void sanitise_blank_returnsEmpty() {
        assertEquals("", OnboardingUploadService.sanitiseFilename("   "));
    }

    @Test
    void sanitise_stripsForwardSlashes() {
        assertEquals("etcpasswd",
                OnboardingUploadService.sanitiseFilename("/etc/passwd"));
    }

    @Test
    void sanitise_stripsBackslashes() {
        assertEquals("WindowsSystem32cmd.exe",
                OnboardingUploadService.sanitiseFilename("Windows\\System32\\cmd.exe"));
    }

    @Test
    void sanitise_collapsesDoubleDot() {
        // Positive allowlist keeps `.` chars; multiple-dot run collapses to a
        // single dot so `..etcpasswd` cannot resurface from unicode lookalikes.
        assertEquals(".etcpasswd",
                OnboardingUploadService.sanitiseFilename("../etc/passwd"));
    }

    @Test
    void sanitise_collapsesAnyRunOfDots() {
        assertEquals(".pdf",
                OnboardingUploadService.sanitiseFilename("....pdf"));
    }

    @Test
    void sanitise_dropsUnicodeLookalikes() {
        // U+FF0F FULLWIDTH SOLIDUS / U+2024 ONE DOT LEADER must be stripped
        // by the positive ASCII allowlist.
        assertEquals("etcpasswd",
                OnboardingUploadService.sanitiseFilename("／etc／passwd"));
    }

    @Test
    void sanitise_stripsControlChars() {
        // BEL (0x07) gets stripped; printable space is preserved.
        String dirty = "helloworld.pdf";
        assertEquals("helloworld.pdf",
                OnboardingUploadService.sanitiseFilename(dirty));
    }

    @Test
    void sanitise_keepsSpaces() {
        assertEquals("Drivers License Front.jpg",
                OnboardingUploadService.sanitiseFilename("Drivers License Front.jpg"));
    }

    @Test
    void sanitise_keepsAsciiLettersDigitsHyphenUnderscore() {
        // Positive allowlist: a-z A-Z 0-9 . _ - and space.
        assertEquals("Korekort-aoa_2026.pdf",
                OnboardingUploadService.sanitiseFilename("Korekort-aoa_2026.pdf"));
    }

    @Test
    void sanitise_dropsNonAsciiLetters() {
        // Allowlist is intentionally ASCII-only — Danish letters (æ ø å) get
        // stripped. SharePoint/Graph would accept them, but tightening the
        // surface here guards against unicode mischief.
        assertEquals("Krekort.pdf",
                OnboardingUploadService.sanitiseFilename("Kørekort.pdf"));
    }

    @Test
    void sanitise_resultIsNeverNull() {
        // Defensive: any input must yield a non-null String.
        assertTrue(OnboardingUploadService.sanitiseFilename("...").isEmpty()
                || !OnboardingUploadService.sanitiseFilename("...").isBlank());
    }

    @Test
    void sanitisePathSegment_collapsesTraversal() {
        assertEquals(".safeUser",
                OnboardingUploadService.sanitisePathSegment("../safe/User"));
    }

    // ── magic-byte sniffing (defense against lying Content-Type) ──────────

    @Test
    void magic_pdf_isNoLongerAccepted() {
        // PDF support was deliberately removed when AI document validation
        // landed — the vision API takes images only.
        byte[] bytes = {0x25, 0x50, 0x44, 0x46, 0x2d, 0x31, 0x2e, 0x37};
        assertEquals(false, OnboardingUploadService.magicMatches("application/pdf", bytes));
    }

    @Test
    void magic_jpeg_matchesJPEGHeader() {
        byte[] bytes = {(byte) 0xff, (byte) 0xd8, (byte) 0xff, (byte) 0xe0};
        assertTrue(OnboardingUploadService.magicMatches("image/jpeg", bytes));
    }

    @Test
    void magic_png_matchesPNGHeader() {
        byte[] bytes = {(byte) 0x89, 0x50, 0x4e, 0x47, 0x0d, 0x0a, 0x1a, 0x0a};
        assertTrue(OnboardingUploadService.magicMatches("image/png", bytes));
    }

    @Test
    void magic_rejectsTooShortInput() {
        assertEquals(false, OnboardingUploadService.magicMatches("image/png", new byte[]{(byte) 0x89, 0x50, 0x4e, 0x47}));
    }

    @Test
    void magic_unknownTypeReturnsFalse() {
        // Defense in depth: even if the allowlist is bypassed elsewhere,
        // an unrecognised type cannot pass the magic check.
        assertEquals(false,
                OnboardingUploadService.magicMatches("application/octet-stream", new byte[]{0x25, 0x50, 0x44, 0x46}));
    }
}
