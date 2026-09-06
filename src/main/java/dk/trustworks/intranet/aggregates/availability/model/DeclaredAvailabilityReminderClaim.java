package dk.trustworks.intranet.aggregates.availability.model;

import io.quarkus.hibernate.orm.panache.PanacheEntityBase;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.time.LocalDateTime;

/**
 * The weekly reminder claim (V567), at the grain the reminder is sent: one row per
 * person per ISO week. {@code UNIQUE (useruuid, week_key)} makes exactly one ECS task the
 * sender — the same shape as {@code ExpenseEmployeeDigestClaim}.
 */
@Entity
@Table(name = "declared_availability_reminder_claim")
public class DeclaredAvailabilityReminderClaim extends PanacheEntityBase {

    @Id
    public String uuid;

    @Column(name = "useruuid", nullable = false)
    public String useruuid;

    /** ISO week of the run, e.g. {@code 2026-W36}. */
    @Column(name = "week_key", nullable = false)
    public String weekKey;

    @Column(name = "claimed_at", nullable = false)
    public LocalDateTime claimedAt;
}
