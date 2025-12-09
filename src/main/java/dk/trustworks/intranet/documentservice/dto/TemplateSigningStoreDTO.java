package dk.trustworks.intranet.documentservice.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

/**
 * DTO for template signing stores.
 * Used for transferring SharePoint signing store configuration between backend and frontend.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class TemplateSigningStoreDTO {

    private String uuid;
    private String siteUrl;
    private String driveName;
    private String folderPath;
    private String displayName;
    private boolean isActive;
    private int displayOrder;
    private boolean userDirectory;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;
}
