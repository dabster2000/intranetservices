package dk.trustworks.intranet.documentservice.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

/**
 * DTO for template signing stores.
 * Used for transferring SharePoint signing store configuration between backend and frontend.
 * Now references a shared SharePointLocation instead of storing path details directly.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class TemplateSigningStoreDTO {

    private String uuid;

    /**
     * UUID of the referenced SharePoint location.
     * Used when creating/updating to specify which location to use.
     */
    private String locationUuid;

    /**
     * The full SharePoint location object.
     * Populated when returning data, null when creating/updating.
     */
    private SharePointLocationDTO location;

    /**
     * Optional override for the location's display name.
     * If set, this name is shown instead of the location's default display name.
     */
    private String displayNameOverride;

    private boolean isActive;
    private int displayOrder;
    private boolean userDirectory;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;

    // --- Backward compatibility getters ---
    // These getters provide access to location properties for existing code

    /**
     * Get the site URL from the referenced location.
     * Provides backward compatibility for code that accessed siteUrl directly.
     *
     * @return The SharePoint site URL, or null if location is not set
     */
    public String getSiteUrl() {
        return location != null ? location.getSiteUrl() : null;
    }

    /**
     * Get the drive name from the referenced location.
     * Provides backward compatibility for code that accessed driveName directly.
     *
     * @return The SharePoint drive name, or null if location is not set
     */
    public String getDriveName() {
        return location != null ? location.getDriveName() : null;
    }

    /**
     * Get the folder path from the referenced location.
     * Provides backward compatibility for code that accessed folderPath directly.
     *
     * @return The SharePoint folder path, or null if location is not set
     */
    public String getFolderPath() {
        return location != null ? location.getFolderPath() : null;
    }

    /**
     * Get the effective display name.
     * Returns displayNameOverride if set, otherwise the location's display name.
     *
     * @return The display name to show to users
     */
    public String getEffectiveDisplayName() {
        if (displayNameOverride != null && !displayNameOverride.trim().isEmpty()) {
            return displayNameOverride;
        }
        return location != null ? location.getName() : null;
    }
}
