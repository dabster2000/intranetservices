package dk.trustworks.intranet.sharepoint.dto;

import java.net.URLDecoder;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;

/**
 * Utility class for encoding SharePoint/Graph API paths.
 * Handles proper URL encoding while preserving path separators.
 */
public final class GraphPath {

    private GraphPath() {
        // Utility class - no instantiation
    }

    /**
     * Encodes a file or folder path for use in Graph API URLs.
     * Preserves forward slashes but encodes other special characters.
     *
     * @param path the path to encode (e.g., "folder/subfolder/file name.pdf")
     * @return the encoded path suitable for Graph API URLs
     */
    public static String encodePath(String path) {
        if (path == null || path.isBlank()) {
            return "";
        }

        // Remove leading/trailing slashes and normalize
        String normalized = path.replaceAll("^/+", "").replaceAll("/+$", "");

        if (normalized.isEmpty()) {
            return "";
        }

        // Split by path separator, encode each segment, rejoin
        String[] segments = normalized.split("/");
        StringBuilder encoded = new StringBuilder();

        for (int i = 0; i < segments.length; i++) {
            if (i > 0) {
                encoded.append("/");
            }
            encoded.append(encodeSegment(segments[i]));
        }

        return encoded.toString();
    }

    /**
     * Encodes a single path segment (file or folder name).
     * Handles special characters that need encoding in URLs.
     * <p>
     * This method is idempotent: it decodes first, then encodes.
     * This prevents double-encoding when input already contains encoded characters.
     * <ul>
     *   <li>"TWT Kontrakter" → "TWT%20Kontrakter"</li>
     *   <li>"TWT%20Kontrakter" → "TWT%20Kontrakter" (not %2520)</li>
     * </ul>
     *
     * @param segment the segment to encode
     * @return the URL-encoded segment
     */
    public static String encodeSegment(String segment) {
        if (segment == null || segment.isEmpty()) {
            return "";
        }

        // Decode first to prevent double-encoding (makes operation idempotent)
        // If input is already encoded (%20), decode it first, then re-encode
        // If input is raw (space), decode is no-op, then encode normally
        String decoded = URLDecoder.decode(segment, StandardCharsets.UTF_8);

        // URLEncoder encodes space as '+', but we need %20 for paths
        return URLEncoder.encode(decoded, StandardCharsets.UTF_8)
            .replace("+", "%20");
    }

    /**
     * Builds a full path from folder path and filename.
     *
     * @param folderPath the folder path (can be null or empty for root)
     * @param fileName the file name
     * @return the combined path
     */
    public static String buildFilePath(String folderPath, String fileName) {
        if (folderPath == null || folderPath.isBlank()) {
            return fileName;
        }
        String normalizedFolder = folderPath.replaceAll("^/+", "").replaceAll("/+$", "");
        return normalizedFolder.isEmpty() ? fileName : normalizedFolder + "/" + fileName;
    }
}
