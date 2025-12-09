package dk.trustworks.intranet.sharepoint.dto;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;

import java.time.OffsetDateTime;

/**
 * Represents a file or folder in a SharePoint/OneDrive drive.
 * Maps to Microsoft Graph API DriveItem resource.
 *
 * @see <a href="https://learn.microsoft.com/en-us/graph/api/resources/driveitem">DriveItem Resource</a>
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record DriveItem(
    String id,
    String name,
    Long size,
    @JsonProperty("createdDateTime") OffsetDateTime createdDateTime,
    @JsonProperty("lastModifiedDateTime") OffsetDateTime lastModifiedDateTime,
    @JsonProperty("webUrl") String webUrl,
    Folder folder,
    File file,
    @JsonProperty("parentReference") ParentReference parentReference,
    @JsonProperty("@microsoft.graph.downloadUrl") String downloadUrl
) {
    /**
     * Determines if this item is a folder.
     */
    public boolean isFolder() {
        return folder != null;
    }

    /**
     * Determines if this item is a file.
     */
    public boolean isFile() {
        return file != null;
    }

    /**
     * Represents folder-specific properties.
     */
    @JsonIgnoreProperties(ignoreUnknown = true)
    public record Folder(
        @JsonProperty("childCount") Integer childCount
    ) {}

    /**
     * Represents file-specific properties.
     */
    @JsonIgnoreProperties(ignoreUnknown = true)
    public record File(
        String mimeType,
        Hashes hashes
    ) {}

    /**
     * Represents file hashes for integrity verification.
     */
    @JsonIgnoreProperties(ignoreUnknown = true)
    public record Hashes(
        @JsonProperty("quickXorHash") String quickXorHash,
        @JsonProperty("sha1Hash") String sha1Hash,
        @JsonProperty("sha256Hash") String sha256Hash
    ) {}

    /**
     * Represents the parent folder reference.
     */
    @JsonIgnoreProperties(ignoreUnknown = true)
    public record ParentReference(
        String id,
        String driveId,
        @JsonProperty("driveType") String driveType,
        String path,
        String name,
        @JsonProperty("siteId") String siteId
    ) {}
}
