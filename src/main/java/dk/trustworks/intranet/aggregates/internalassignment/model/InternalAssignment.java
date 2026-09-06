package dk.trustworks.intranet.aggregates.internalassignment.model;

import io.quarkus.hibernate.orm.panache.PanacheEntityBase;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.Collection;
import java.util.List;

/**
 * An internal assignment (V568): a planning block that reserves a junior's capacity for
 * internal work (spec §4.3). {@code APPROVED} rows emit demand into
 * {@code fact_internal_budget_day}; {@code DRAFT} and {@code REJECTED} rows emit nothing.
 * Never a REST body — the resource takes {@code InternalAssignmentRequest}.
 */
@Getter
@Setter
@NoArgsConstructor
@Entity
@Table(name = "internal_assignment")
public class InternalAssignment extends PanacheEntityBase {

    public enum Status { DRAFT, APPROVED, REJECTED }

    @Id
    @Column(name = "uuid", length = 36, nullable = false)
    private String uuid;

    @Column(name = "useruuid", length = 36, nullable = false)
    private String useruuid;

    @Column(name = "title", length = 200, nullable = false)
    private String title;

    @Column(name = "sponsor_useruuid", length = 36)
    private String sponsorUseruuid;

    @Column(name = "active_from", nullable = false)
    private LocalDate activeFrom;

    @Column(name = "active_to", nullable = false)
    private LocalDate activeTo;

    @Column(name = "hours_per_week", nullable = false, precision = 5, scale = 2)
    private BigDecimal hoursPerWeek;

    @Column(name = "estimated_total_hours", precision = 7, scale = 2)
    private BigDecimal estimatedTotalHours;

    @Column(name = "strategic", nullable = false)
    private boolean strategic;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", length = 16, nullable = false)
    private Status status;

    @Column(name = "approved_by", length = 36)
    private String approvedBy;

    @Column(name = "approved_at")
    private LocalDateTime approvedAt;

    @Column(name = "notes", columnDefinition = "TEXT")
    private String notes;

    @Column(name = "created_at", nullable = false)
    private LocalDateTime createdAt;

    @Column(name = "updated_at", nullable = false)
    private LocalDateTime updatedAt;

    @Column(name = "updated_by", length = 36)
    private String updatedBy;

    /** Rows for one person overlapping {@code [from, to]}, earliest first. */
    public static List<InternalAssignment> findOverlapping(String useruuid, LocalDate from, LocalDate to) {
        return list("useruuid = ?1 and activeFrom <= ?2 and activeTo >= ?3 order by activeFrom, title",
                useruuid, to, from);
    }

    /** APPROVED rows for one person that cover {@code day}. */
    public static List<InternalAssignment> findApprovedOnDay(String useruuid, LocalDate day) {
        return list("useruuid = ?1 and status = ?2 and activeFrom <= ?3 and activeTo >= ?3",
                useruuid, Status.APPROVED, day);
    }

    /** Every DRAFT row, oldest first — the approval queue before reach is applied. */
    public static List<InternalAssignment> findPending() {
        return list("status = ?1 order by createdAt", Status.DRAFT);
    }

    /** DRAFT rows for the given people, oldest first. */
    public static List<InternalAssignment> findPendingFor(Collection<String> useruuids) {
        if (useruuids == null || useruuids.isEmpty()) {
            return List.of();
        }
        return list("status = ?1 and useruuid in ?2 order by createdAt", Status.DRAFT, List.copyOf(useruuids));
    }

    /** APPROVED rows for the given people overlapping {@code [from, to]}. */
    public static List<InternalAssignment> findApprovedFor(Collection<String> useruuids, LocalDate from, LocalDate to) {
        if (useruuids == null || useruuids.isEmpty()) {
            return List.of();
        }
        return list("status = ?1 and useruuid in ?2 and activeFrom <= ?3 and activeTo >= ?4 order by activeFrom",
                Status.APPROVED, List.copyOf(useruuids), to, from);
    }
}
