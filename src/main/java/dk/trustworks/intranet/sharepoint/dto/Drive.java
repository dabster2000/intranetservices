package dk.trustworks.intranet.sharepoint.dto;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;

import java.time.OffsetDateTime;

/**
 * Represents a SharePoint document library or OneDrive.
 * Maps to Microsoft Graph API Drive resource.
 *
 * @see <a href="https://learn.microsoft.com/en-us/graph/api/resources/drive">Drive Resource</a>
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record Drive(
    String id,
    String name,
    @JsonProperty("driveType") String driveType,
    @JsonProperty("webUrl") String webUrl,
    @JsonProperty("createdDateTime") OffsetDateTime createdDateTime,
    @JsonProperty("lastModifiedDateTime") OffsetDateTime lastModifiedDateTime,
    Owner owner,
    Quota quota
) {
    /**
     * Represents the owner of a drive.
     */
    @JsonIgnoreProperties(ignoreUnknown = true)
    public record Owner(
        User user,
        Group group
    ) {}

    /**
     * Represents a user identity.
     */
    @JsonIgnoreProperties(ignoreUnknown = true)
    public record User(
        String id,
        @JsonProperty("displayName") String displayName,
        String email
    ) {}

    /**
     * Represents a group identity.
     */
    @JsonIgnoreProperties(ignoreUnknown = true)
    public record Group(
        String id,
        @JsonProperty("displayName") String displayName
    ) {}

    /**
     * Represents storage quota information.
     */
    @JsonIgnoreProperties(ignoreUnknown = true)
    public record Quota(
        Long deleted,
        Long remaining,
        String state,
        Long total,
        Long used
    ) {}
}
