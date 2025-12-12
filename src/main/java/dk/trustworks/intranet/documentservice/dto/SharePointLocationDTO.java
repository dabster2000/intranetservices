package dk.trustworks.intranet.documentservice.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

/**
 * DTO for shared SharePoint locations.
 * Used for transferring SharePoint location configuration between backend and frontend.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class SharePointLocationDTO {

    private String uuid;
    private String name;
    private String siteUrl;
    private String driveName;
    private String folderPath;
    private boolean isActive;
    private int displayOrder;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;

    /**
     * Number of signing stores referencing this location.
     * Populated by the service layer when returning location data.
     */
    private long referenceCount;
}
