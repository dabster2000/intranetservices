package dk.trustworks.intranet.sharepoint.dto;

import java.time.OffsetDateTime;
import java.util.List;

/**
 * Response DTO for listing SharePoint files.
 * Provides a simplified view of DriveItems for API consumers.
 */
public record SharePointFileListResponse(
    List<FileInfo> files,
    String nextLink
) {
    /**
     * Simplified file information for API responses.
     */
    public record FileInfo(
        String id,
        String name,
        String type,
        Long size,
        String mimeType,
        OffsetDateTime createdDateTime,
        OffsetDateTime lastModifiedDateTime,
        String webUrl,
        Integer childCount
    ) {
        /**
         * Creates a FileInfo from a DriveItem.
         */
        public static FileInfo from(DriveItem item) {
            return new FileInfo(
                item.id(),
                item.name(),
                item.isFolder() ? "folder" : "file",
                item.size(),
                item.file() != null ? item.file().mimeType() : null,
                item.createdDateTime(),
                item.lastModifiedDateTime(),
                item.webUrl(),
                item.folder() != null ? item.folder().childCount() : null
            );
        }
    }
}
