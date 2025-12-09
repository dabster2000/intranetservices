package dk.trustworks.intranet.sharepoint.dto;

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
     *
     * @param segment the segment to encode
     * @return the URL-encoded segment
     */
    public static String encodeSegment(String segment) {
        if (segment == null || segment.isEmpty()) {
            return "";
        }

        // URLEncoder encodes space as '+', but we need %20 for paths
        return URLEncoder.encode(segment, StandardCharsets.UTF_8)
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
