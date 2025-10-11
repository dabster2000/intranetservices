package dk.trustworks.intranet.snapshot.model;

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
 * Generic entity representing immutable snapshots of business data.
 * Supports multiple entity types with versioning for audit and compliance.
 * <p>
 * Once created, snapshots are immutable. The system uses stored snapshots
 * instead of recalculating from live data, ensuring consistency even if
 * underlying data changes.
 * <p>
 * Examples of entity types:
 * - "bonus_pool" - Bonus calculations for fiscal years
 * - "contract" - Contract snapshots at signing
 * - "financial_report" - End-of-period financial statements
 * - "audit_trail" - Compliance snapshots
 */
@Getter
@Setter
@Entity
@Table(
    name = "immutable_snapshots",
    uniqueConstraints = {
        @UniqueConstraint(
            name = "uk_snapshot_natural_key",
            columnNames = {"entity_type", "entity_id", "snapshot_version"}
        )
    },
    indexes = {
        @Index(name = "idx_entity_lookup", columnList = "entity_type, entity_id"),
        @Index(name = "idx_entity_type_time", columnList = "entity_type, locked_at"),
        @Index(name = "idx_locked_by", columnList = "locked_by"),
        @Index(name = "idx_created_at", columnList = "created_at")
    }
)
@Cache(usage = CacheConcurrencyStrategy.READ_ONLY)
@Schema(
    name = "ImmutableSnapshot",
    description = "Generic immutable snapshot for audit compliance across entity types"
)
public class ImmutableSnapshot extends PanacheEntityBase {

    /**
     * Surrogate primary key for performance.
     * Not exposed in business logic - use natural key (entity_type + entity_id + version).
     */
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "snapshot_id")
    @Schema(description = "Internal database ID", readOnly = true)
    private Long id;

    /**
     * Entity type discriminator (e.g., "bonus_pool", "contract", "financial_report").
     * Part of composite natural key.
     */
    @NotNull
    @Size(max = 100)
    @Column(name = "entity_type", nullable = false, length = 100)
    @Schema(
        description = "Entity type discriminator",
        example = "bonus_pool",
        required = true
    )
    private String entityType;

    /**
     * Business entity identifier (e.g., "2024" for fiscal year, "CONTRACT-123", UUID).
     * Part of composite natural key. Flexible string format supports various ID types.
     */
    @NotNull
    @Size(max = 255)
    @Column(name = "entity_id", nullable = false, length = 255)
    @Schema(
        description = "Business entity identifier",
        example = "2024",
        required = true
    )
    private String entityId;

    /**
     * Snapshot version number. Allows multiple snapshots of same entity over time.
     * Part of composite natural key. Starts at 1, increments for each new snapshot.
     */
    @NotNull
    @Column(name = "snapshot_version", nullable = false)
    @Schema(
        description = "Snapshot version number",
        example = "1",
        required = true
    )
    private Integer snapshotVersion = 1;

    /**
     * Complete JSON serialization of entity state.
     * Contains all data necessary to reconstruct the entity at snapshot time.
     * Uses TEXT column type to support large payloads.
     */
    @NotNull
    @Column(name = "snapshot_data", nullable = false, columnDefinition = "TEXT")
    @Lob
    @Schema(
        description = "Complete JSON serialization of entity state",
        required = true
    )
    private String snapshotData;

    /**
     * SHA-256 checksum of snapshotData for integrity verification.
     * Calculated during snapshot creation, validated during deserialization.
     */
    @NotNull
    @Size(max = 64)
    @Column(name = "checksum", nullable = false, length = 64)
    @Schema(
        description = "SHA-256 checksum for data integrity verification",
        example = "a1b2c3d4e5f6...",
        readOnly = true
    )
    private String checksum;

    /**
     * Optional entity-specific metadata as JSON.
     * Allows extensibility without schema changes.
     * Example: {"fiscalYear": 2024, "department": "Engineering"}
     */
    @Column(name = "metadata", columnDefinition = "JSON")
    @Schema(
        description = "Optional entity-specific metadata",
        example = "{\"fiscalYear\": 2024, \"department\": \"Engineering\"}"
    )
    private String metadata;

    /**
     * Timestamp when snapshot was created (locked).
     */
    @NotNull
    @Column(name = "locked_at", nullable = false)
    @Schema(
        description = "When the snapshot was created",
        example = "2024-07-01T10:00:00",
        readOnly = true
    )
    private LocalDateTime lockedAt;

    /**
     * Username or email of person who created the snapshot.
     */
    @NotNull
    @Size(max = 255)
    @Column(name = "locked_by", nullable = false, length = 255)
    @Schema(
        description = "Username or email who created the snapshot",
        example = "admin@trustworks.dk",
        required = true
    )
    private String lockedBy;

    /**
     * Audit timestamp - record creation.
     */
    @NotNull
    @Column(name = "created_at", nullable = false, updatable = false)
    @Schema(description = "Record creation timestamp", readOnly = true)
    private LocalDateTime createdAt;

    /**
     * Audit timestamp - last modification.
     * Note: In practice, immutable snapshots should never be updated.
     * This field exists for consistency with audit patterns.
     */
    @NotNull
    @Column(name = "updated_at", nullable = false)
    @Schema(description = "Last update timestamp", readOnly = true)
    private LocalDateTime updatedAt;

    /**
     * Version field for JPA optimistic locking.
     * Prevents concurrent modification issues in rare update scenarios.
     */
    @Version
    @Column(name = "version", nullable = false)
    @Schema(description = "Version for optimistic locking", readOnly = true)
    private Integer version = 1;

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
     * Note: Updates should be rare for immutable snapshots.
     */
    @PreUpdate
    protected void onUpdate() {
        updatedAt = LocalDateTime.now();
    }

    /**
     * Get composite natural key as string for logging/debugging.
     *
     * @return natural key in format "entity_type:entity_id:version"
     */
    public String getNaturalKey() {
        return String.format("%s:%s:%d", entityType, entityId, snapshotVersion);
    }

    @Override
    public String toString() {
        return String.format("ImmutableSnapshot[%s, locked=%s by %s]",
            getNaturalKey(), lockedAt, lockedBy);
    }
}
