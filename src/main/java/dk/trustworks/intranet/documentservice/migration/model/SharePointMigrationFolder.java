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
 * One top-level personal folder per SharePoint site — the migration's
 * "whose folder is this?" working row (spec §6.2 V45c, V457). TEMPORARY:
 * the table is dropped again at decommission (spec §9.8).
 *
 * <p>AI match proposals (decisions A1/A2) live in the
 * {@code ai_suggested_*} columns until a human confirms them
 * ({@code match_method=AI_CONFIRMED}) or picks someone else
 * ({@code MANUAL}) — a proposal never auto-applies. Confirmations are
 * sticky across re-crawls (the crawler never resets an existing row's
 * match fields).</p>
 */
@Data
@NoArgsConstructor
@EqualsAndHashCode(onlyExplicitlyIncluded = true, callSuper = false)
@Entity
@Table(name = "sharepoint_migration_folders")
public class SharePointMigrationFolder extends PanacheEntityBase {

    public enum MatchMethod { USERNAME, FULLNAME, AI_CONFIRMED, MANUAL, UNMATCHED }
    public enum AiConfidence { HIGH, MEDIUM, LOW }
    public enum FolderStatus { DISCOVERED, MAPPED, COPYING, VERIFIED, FAILED, SKIPPED }

    @Id
    @EqualsAndHashCode.Include
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "site_url", nullable = false, length = 500)
    private String siteUrl;

    /** Top-level personal folder path relative to the drive root. */
    @Column(name = "folder_path", nullable = false, length = 1024)
    private String folderPath;

    @Column(name = "folder_name", nullable = false, length = 500)
    private String folderName;

    @Column(name = "matched_user_uuid", length = 36)
    private String matchedUserUuid;

    @Enumerated(EnumType.STRING)
    @Column(name = "match_method", nullable = false)
    private MatchMethod matchMethod = MatchMethod.UNMATCHED;

    @Column(name = "ai_suggested_user_uuid", length = 36)
    private String aiSuggestedUserUuid;

    @Enumerated(EnumType.STRING)
    @Column(name = "ai_confidence")
    private AiConfidence aiConfidence;

    /** One-sentence justification shown in the admin card. */
    @Column(name = "ai_reason", length = 512)
    private String aiReason;

    @Column(name = "file_count", nullable = false)
    private int fileCount;

    @Column(name = "total_bytes", nullable = false)
    private long totalBytes;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false)
    private FolderStatus status = FolderStatus.DISCOVERED;

    @Column(name = "note", length = 1024)
    private String note;

    // ── Queries ────────────────────────────────────────────────────────────

    public static SharePointMigrationFolder findBySiteAndPath(String siteUrl, String folderPath) {
        return find("siteUrl = ?1 AND folderPath = ?2", siteUrl, folderPath).firstResult();
    }

    public static List<SharePointMigrationFolder> findMapped() {
        return list("status IN (?1, ?2)", FolderStatus.MAPPED, FolderStatus.COPYING);
    }

    /** The manual queue: discovered but not yet mapped/skipped. */
    public static List<SharePointMigrationFolder> findUnresolved() {
        return list("status = ?1 AND matchedUserUuid IS NULL", FolderStatus.DISCOVERED);
    }

    /** Green criteria: proposals nobody has confirmed, re-picked or skipped yet. */
    public static long countUnconfirmedAiSuggestions() {
        return count("aiSuggestedUserUuid IS NOT NULL AND matchedUserUuid IS NULL AND status <> ?1",
                FolderStatus.SKIPPED);
    }
}
