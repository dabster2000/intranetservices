package dk.trustworks.intranet.aggregates.invoice.bonus.model;

import io.quarkus.hibernate.orm.panache.PanacheEntityBase;
import jakarta.persistence.*;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import lombok.Getter;
import lombok.Setter;
import org.eclipse.microprofile.openapi.annotations.media.Schema;
import org.hibernate.annotations.Cache;
import org.hibernate.annotations.CacheConcurrencyStrategy;

import java.time.LocalDateTime;

/**
 * Entity representing locked bonus pool data for a fiscal year.
 * Stores immutable snapshot of FiscalYearPoolContext for audit and compliance.
 *
 * Once bonus calculations are locked, the system uses the stored snapshot
 * instead of recalculating from live data, ensuring consistency even if
 * underlying data changes.
 */
@Getter
@Setter
@Entity
@Table(name = "locked_bonus_pool_data")
@Cache(usage = CacheConcurrencyStrategy.READ_WRITE)
@Schema(
    name = "LockedBonusPoolData",
    description = "Locked bonus pool snapshot for a fiscal year for audit compliance"
)
public class LockedBonusPoolData extends PanacheEntityBase {

    /**
     * Fiscal year (e.g., 2024 for FY 2024-2025: July 1, 2024 - June 30, 2025).
     * Primary key.
     */
    @Id
    @Column(name = "fiscal_year", nullable = false)
    @Schema(description = "Fiscal year", example = "2024")
    public Integer fiscalYear;

    /**
     * Complete JSON serialization of FiscalYearPoolContext.
     * Contains all leader point data, utilization metrics, revenue figures, etc.
     *
     * Uses TEXT column type to support large JSON payloads (up to 64KB typical).
     */
    @NotNull
    @Column(name = "pool_context_json", nullable = false, columnDefinition = "TEXT")
    @Lob
    @Schema(description = "Complete JSON serialization of FiscalYearPoolContext")
    public String poolContextJson;

    /**
     * Timestamp when data was locked.
     */
    @NotNull
    @Column(name = "locked_at", nullable = false)
    @Schema(description = "Timestamp when data was locked", readOnly = true)
    public LocalDateTime lockedAt;

    /**
     * Username or email of the person who locked the data.
     */
    @NotNull
    @Size(max = 255)
    @Column(name = "locked_by", nullable = false, length = 255)
    @Schema(description = "Username or email who locked the data", example = "admin@trustworks.dk")
    public String lockedBy;

    /**
     * SHA-256 checksum of poolContextJson for integrity verification.
     * Calculated during lock operation, validated during deserialization.
     */
    @NotNull
    @Size(max = 64)
    @Column(name = "checksum", nullable = false, length = 64)
    @Schema(description = "SHA-256 checksum for data integrity verification")
    public String checksum;

    /**
     * Version field for optimistic locking.
     * Prevents concurrent modification issues.
     */
    @Version
    @Column(name = "version", nullable = false)
    @Schema(description = "Version for optimistic locking", readOnly = true)
    public Integer version = 1;

    /**
     * Audit timestamp - record creation.
     */
    @Column(name = "created_at", nullable = false, updatable = false)
    @Schema(description = "Record creation timestamp", readOnly = true)
    public LocalDateTime createdAt;

    /**
     * Audit timestamp - last modification.
     */
    @Column(name = "updated_at", nullable = false)
    @Schema(description = "Last update timestamp", readOnly = true)
    public LocalDateTime updatedAt;

    /**
     * JPA callback: Set timestamps before persist.
     */
    @PrePersist
    protected void onCreate() {
        LocalDateTime now = LocalDateTime.now();
        if (createdAt == null) {
            createdAt = now;
        }
        if (updatedAt == null) {
            updatedAt = now;
        }
        if (lockedAt == null) {
            lockedAt = now;
        }
    }

    /**
     * JPA callback: Update timestamp before merge.
     */
    @PreUpdate
    protected void onUpdate() {
        updatedAt = LocalDateTime.now();
    }
}
