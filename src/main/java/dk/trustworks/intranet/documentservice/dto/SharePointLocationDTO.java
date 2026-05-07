package dk.trustworks.intranet.documentservice.dto;

import dk.trustworks.intranet.documentservice.model.enums.SharePointLocationType;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

/**
 * DTO for shared SharePoint locations.
 * <p>
 * Each location is bound to a single company (via {@code companyUuid}) and a single
 * {@link SharePointLocationType}. The pair is used by the signing flow to resolve a
 * location for a given template.
 * </p>
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

    /**
     * UUID of the owning company. Required when creating or updating a location.
     */
    private String companyUuid;

    /**
     * Classification used together with {@link #companyUuid} to resolve this location
     * from a document template's {@code sharepointType}.
     */
    private SharePointLocationType type;

    private boolean isActive;
    private int displayOrder;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;
}
