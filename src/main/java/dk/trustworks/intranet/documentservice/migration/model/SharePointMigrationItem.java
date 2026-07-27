package dk.trustworks.intranet.documentservice.migration.model;

import io.quarkus.hibernate.orm.panache.PanacheEntityBase;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.Data;
import lombok.EqualsAndHashCode;
import lombok.NoArgsConstructor;

import java.util.List;

/**
 * One SharePoint file — the migration's "has this exact file been copied
 * and verified?" working row (spec §6.2 V45c, V457). Unique on
 * {@code drive_item_id} so a re-crawl adds nothing and a re-copy skips
 * rows already in a done state. TEMPORARY (dropped at decommission).
 */
@Data
@NoArgsConstructor
@EqualsAndHashCode(onlyExplicitlyIncluded = true, callSuper = false)
@Entity
@Table(name = "sharepoint_migration_items")
public class SharePointMigrationItem extends PanacheEntityBase {

    public enum ItemStatus { DISCOVERED, COPIED, VERIFIED, FAILED, SKIPPED }

    @Id
    @EqualsAndHashCode.Include
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "folder_id", nullable = false)
    private Long folderId;

    @Column(name = "drive_item_id", nullable = false, length = 255)
    private String driveItemId;

    /** Path under the personal folder (empty string for root files). */
    @Column(name = "relative_path", nullable = false, length = 1024)
    private String relativePath;

    @Column(name = "name", nullable = false, length = 500)
    private String name;

    @Column(name = "size_bytes", nullable = false)
    private long sizeBytes;

    /** Graph's quickXorHash; compared against a local recompute at copy time. */
    @Column(name = "quickxor_hash", length = 64)
    private String quickxorHash;

    @Column(name = "etag", length = 128)
    private String etag;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false)
    private ItemStatus status = ItemStatus.DISCOVERED;

    @Column(name = "employee_document_uuid", length = 36)
    private String employeeDocumentUuid;

    @Column(name = "error", length = 1024)
    private String error;

    // ── Queries ────────────────────────────────────────────────────────────

    public static SharePointMigrationItem findByDriveItemId(String driveItemId) {
        return find("driveItemId", driveItemId).firstResult();
    }

    public static List<SharePointMigrationItem> findByFolderAndStatus(Long folderId, ItemStatus status) {
        return list("folderId = ?1 AND status = ?2", folderId, status);
    }

    public static List<SharePointMigrationItem> findByStatus(ItemStatus status) {
        return list("status", status);
    }

    public static List<SharePointMigrationItem> findByFolder(Long folderId) {
        return list("folderId", folderId);
    }
}
