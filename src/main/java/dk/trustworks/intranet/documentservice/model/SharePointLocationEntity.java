package dk.trustworks.intranet.documentservice.model;

import io.quarkus.hibernate.orm.panache.PanacheEntityBase;
import jakarta.persistence.*;
import jakarta.validation.constraints.NotBlank;
import lombok.Data;
import lombok.EqualsAndHashCode;

import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;

/**
 * JPA Entity for shared SharePoint locations.
 * Represents a reusable SharePoint folder configuration that can be referenced by multiple signing stores.
 * This allows centralized management of SharePoint locations across templates.
 */
@Data
@EqualsAndHashCode(onlyExplicitlyIncluded = true, callSuper = false)
@Entity
@Table(name = "sharepoint_locations")
public class SharePointLocationEntity extends PanacheEntityBase {

    @Id
    @EqualsAndHashCode.Include
    @Column(length = 36)
    private String uuid;

    @Column(name = "name", nullable = false, length = 255)
    @NotBlank(message = "Name is required")
    private String name;

    @Column(name = "site_url", nullable = false, length = 500)
    @NotBlank(message = "Site URL is required")
    private String siteUrl;

    @Column(name = "drive_name", nullable = false, length = 255)
    @NotBlank(message = "Drive name is required")
    private String driveName;

    @Column(name = "folder_path", length = 500)
    private String folderPath;

    @Column(name = "is_active", nullable = false)
    private Boolean isActive = true;

    @Column(name = "display_order", nullable = false)
    private Integer displayOrder = 1;

    @Column(name = "created_at", nullable = false, updatable = false)
    private LocalDateTime createdAt;

    @Column(name = "updated_at", nullable = false)
    private LocalDateTime updatedAt;

    @PrePersist
    protected void onCreate() {
        if (uuid == null) {
            uuid = UUID.randomUUID().toString();
        }
        createdAt = LocalDateTime.now();
        updatedAt = LocalDateTime.now();
    }

    @PreUpdate
    protected void onUpdate() {
        updatedAt = LocalDateTime.now();
    }

    // --- Panache finder methods ---

    /**
     * Find a location by UUID.
     *
     * @param uuid The location UUID
     * @return The location entity or null if not found
     */
    public static SharePointLocationEntity findByUuid(String uuid) {
        return find("uuid", uuid).firstResult();
    }

    /**
     * Find all locations, optionally filtering by active status.
     *
     * @param activeOnly If true, only return active locations
     * @return List of locations, sorted by displayOrder then name
     */
    public static List<SharePointLocationEntity> findAllLocations(boolean activeOnly) {
        if (activeOnly) {
            return find("isActive = true ORDER BY displayOrder, name").list();
        }
        return find("ORDER BY displayOrder, name").list();
    }

    /**
     * Find all active locations.
     *
     * @return List of active locations, sorted by displayOrder then name
     */
    public static List<SharePointLocationEntity> findAllActive() {
        return find("isActive = true ORDER BY displayOrder, name").list();
    }

    /**
     * Check if a location with the given path already exists.
     * Used for validation to prevent duplicate paths.
     *
     * @param siteUrl    The SharePoint site URL
     * @param driveName  The drive name
     * @param folderPath The folder path (can be null)
     * @return true if a location with this path already exists
     */
    public static boolean existsByPath(String siteUrl, String driveName, String folderPath) {
        if (folderPath == null) {
            return count("siteUrl = ?1 AND driveName = ?2 AND folderPath IS NULL", siteUrl, driveName) > 0;
        }
        return count("siteUrl = ?1 AND driveName = ?2 AND folderPath = ?3", siteUrl, driveName, folderPath) > 0;
    }

    /**
     * Check if a location with the given path already exists, excluding a specific UUID.
     * Used for validation during updates.
     *
     * @param siteUrl     The SharePoint site URL
     * @param driveName   The drive name
     * @param folderPath  The folder path (can be null)
     * @param excludeUuid UUID to exclude from the check
     * @return true if another location with this path exists
     */
    public static boolean existsByPathExcludingUuid(String siteUrl, String driveName, String folderPath, String excludeUuid) {
        if (folderPath == null) {
            return count("siteUrl = ?1 AND driveName = ?2 AND folderPath IS NULL AND uuid != ?3",
                    siteUrl, driveName, excludeUuid) > 0;
        }
        return count("siteUrl = ?1 AND driveName = ?2 AND folderPath = ?3 AND uuid != ?4",
                siteUrl, driveName, folderPath, excludeUuid) > 0;
    }

    /**
     * Count the number of signing stores referencing this location.
     *
     * @param locationUuid The location UUID
     * @return Number of signing stores using this location
     */
    public static long countReferences(String locationUuid) {
        return TemplateSigningStoreEntity.count("location.uuid = ?1", locationUuid);
    }
}
