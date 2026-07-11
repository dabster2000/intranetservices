package dk.trustworks.intranet.aggregates.clientstatus.model;

import io.quarkus.hibernate.orm.panache.PanacheEntityBase;
import jakarta.persistence.*;
import lombok.Getter;
import lombok.Setter;
import org.eclipse.microprofile.openapi.annotations.media.Schema;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.Collection;
import java.util.List;
import java.util.UUID;

/**
 * The controlling state of one client-month cell in the Client Status heatmap.
 *
 * <p>Aggregate root for the client×month controlling context. It carries at most one approval
 * snapshot and at most one editable note per (client, month). Approving freezes the live
 * expected/invoiced values ({@link #approvedExpected} / {@link #approvedInvoiced}) so that later
 * drift can be detected: if the live values move beyond the tolerance from the snapshot the cell is
 * flagged and re-enters the worklist. A note may exist without an approval.</p>
 *
 * <p>All cross-aggregate references ({@code clientUuid}, {@code approvedBy}, {@code createdBy},
 * {@code updatedBy}) are plain UUID strings — no {@code @ManyToOne} coupling to Client or User.</p>
 */
@Getter
@Setter
@Entity
@Table(name = "client_month_control",
        uniqueConstraints = @UniqueConstraint(name = "uq_client_month_control",
                columnNames = {"client_uuid", "month"}))
@Schema(name = "ClientMonthControl",
        description = "Approval snapshot + editable note for one (client, month) controlling cell.")
public class ClientMonthControl extends PanacheEntityBase {

    @Id
    @Schema(description = "Control row UUID", readOnly = true)
    public String uuid;

    @Column(name = "client_uuid", nullable = false, length = 36)
    @Schema(description = "Client UUID this control row belongs to")
    public String clientUuid;

    @Column(name = "month", nullable = false)
    @Schema(description = "Controlled month (first-of-month)")
    public LocalDate month;

    @Column(name = "approved_at")
    @Schema(description = "When the cell was approved (null → not approved)")
    public LocalDateTime approvedAt;

    @Column(name = "approved_by", length = 36)
    @Schema(description = "UUID of the user who approved the cell")
    public String approvedBy;

    @Column(name = "approved_expected")
    @Schema(description = "Frozen expected value at approval time")
    public Double approvedExpected;

    @Column(name = "approved_invoiced")
    @Schema(description = "Frozen invoiced value at approval time")
    public Double approvedInvoiced;

    @Column(name = "note", columnDefinition = "TEXT")
    @Schema(description = "Single editable controlling note for the cell")
    public String note;

    @Column(name = "created_at", nullable = false)
    @Schema(description = "Creation timestamp (server time)", readOnly = true)
    public LocalDateTime createdAt;

    @Column(name = "created_by", length = 36)
    @Schema(description = "UUID of the user who created the row", readOnly = true)
    public String createdBy;

    @Column(name = "updated_at")
    @Schema(description = "Last update timestamp (server time)", readOnly = true)
    public LocalDateTime updatedAt;

    @Column(name = "updated_by", length = 36)
    @Schema(description = "UUID of the user who last updated the row", readOnly = true)
    public String updatedBy;

    @PrePersist
    public void prePersist() {
        if (uuid == null) uuid = UUID.randomUUID().toString();
        if (createdAt == null) createdAt = LocalDateTime.now(java.time.ZoneOffset.UTC);
    }

    /** True when this cell carries an approval snapshot (regardless of drift). */
    public boolean isApproved() {
        return approvedAt != null;
    }

    public static ClientMonthControl findByClientAndMonth(String clientUuid, LocalDate month) {
        return find("clientUuid = ?1 and month = ?2", clientUuid, month).firstResult();
    }

    /** All control rows whose {@code month} falls in [{@code from}, {@code to}] inclusive. */
    public static List<ClientMonthControl> findByMonthRange(LocalDate from, LocalDate to) {
        return list("month >= ?1 and month <= ?2", from, to);
    }

    /** Control rows for the given clients whose {@code month} falls in [{@code from}, {@code to}]. */
    public static List<ClientMonthControl> findByClientsAndMonthRange(
            Collection<String> clientUuids, LocalDate from, LocalDate to) {
        if (clientUuids == null || clientUuids.isEmpty()) return List.of();
        return list("clientUuid in ?1 and month >= ?2 and month <= ?3", clientUuids, from, to);
    }
}
