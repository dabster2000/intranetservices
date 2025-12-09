package dk.trustworks.intranet.sharepoint.dto;

import java.time.OffsetDateTime;

/**
 * Response DTO for successful file uploads.
 */
public record SharePointFileUploadResponse(
    String id,
    String name,
    Long size,
    String mimeType,
    OffsetDateTime createdDateTime,
    OffsetDateTime lastModifiedDateTime,
    String webUrl,
    String downloadUrl
) {
    /**
     * Creates an upload response from a DriveItem.
     */
    public static SharePointFileUploadResponse from(DriveItem item) {
        return new SharePointFileUploadResponse(
            item.id(),
            item.name(),
            item.size(),
            item.file() != null ? item.file().mimeType() : null,
            item.createdDateTime(),
            item.lastModifiedDateTime(),
            item.webUrl(),
            item.downloadUrl()
        );
    }
}
