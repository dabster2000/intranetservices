package dk.trustworks.intranet.recruitmentservice.util;

import java.util.Set;

/**
 * File-format guard for the P5 public application forms: MIME allowlist
 * (PDF, JPEG, PNG) paired with a magic-byte check so a public caller
 * cannot slip an unintended format past the allowlist by lying about
 * {@code Content-Type}. Mirrors the onboarding upload guard
 * ({@code OnboardingUploadService.magicMatches}) but is deliberately its
 * own class — the onboarding allowlist is image-only (AI vision gate)
 * and must not be widened for P5's PDF need.
 * <p>
 * Also carries the shared positive-allowlist filename sanitiser (same
 * rules as the onboarding one: never trust public input as a path
 * component).
 */
public final class PublicApplyDocuments {

    /** Hard size cap per uploaded file — matches the onboarding cap. */
    public static final long MAX_BYTES = 10L * 1024 * 1024;

    /**
     * MIME types the public forms accept. The client-asserted Content-Type
     * is always paired with {@link #magicMatches}.
     */
    public static final Set<String> ALLOWED_MIME_TYPES = Set.of(
            "application/pdf",
            "image/jpeg",
            "image/png");

    private PublicApplyDocuments() {
    }

    /**
     * Verify the first bytes of the upload match the asserted MIME type.
     * <ul>
     *   <li>PDF: {@code 25 50 44 46} ({@code %PDF})</li>
     *   <li>JPEG: {@code FF D8 FF}</li>
     *   <li>PNG: {@code 89 50 4E 47 0D 0A 1A 0A}</li>
     * </ul>
     */
    public static boolean magicMatches(String contentType, byte[] bytes) {
        if (contentType == null || bytes == null || bytes.length < 4) {
            return false;
        }
        return switch (contentType) {
            case "application/pdf" ->
                    bytes[0] == 0x25 && bytes[1] == 0x50 && bytes[2] == 0x44 && bytes[3] == 0x46;
            case "image/jpeg" ->
                    (bytes[0] & 0xff) == 0xff && (bytes[1] & 0xff) == 0xd8 && (bytes[2] & 0xff) == 0xff;
            case "image/png" -> bytes.length >= 8
                    && (bytes[0] & 0xff) == 0x89 && bytes[1] == 0x50 && bytes[2] == 0x4e && bytes[3] == 0x47
                    && bytes[4] == 0x0d && bytes[5] == 0x0a && bytes[6] == 0x1a && bytes[7] == 0x0a;
            default -> false;
        };
    }

    /** Strip an optional charset suffix and lowercase the bare MIME type. */
    public static String normaliseContentType(String contentType) {
        if (contentType == null) {
            return "";
        }
        int semi = contentType.indexOf(';');
        String bare = semi >= 0 ? contentType.substring(0, semi) : contentType;
        return bare.trim().toLowerCase();
    }

    /**
     * Positive allowlist filename sanitiser: keep only ASCII alphanumerics,
     * dot, underscore, hyphen, and space; collapse dot runs so "{@code ..}"
     * cannot resurface from a unicode look-alike. Returns "" if nothing
     * usable remains (callers substitute a safe default name).
     */
    public static String sanitiseFilename(String raw) {
        if (raw == null) {
            return "";
        }
        String filtered = raw.replaceAll("[^a-zA-Z0-9._\\- ]", "");
        filtered = filtered.replaceAll("\\.{2,}", ".");
        return filtered.trim();
    }
}
