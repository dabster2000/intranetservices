package dk.trustworks.intranet.expenseservice.model;

import io.quarkus.hibernate.orm.panache.PanacheEntityBase;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.time.LocalDateTime;

/**
 * The weekly reminder claim, at the grain the reminder is actually sent (V541).
 *
 * <p>{@link ExpenseEmployeeNotification} claims one <em>expense</em>; the Monday
 * digest sends one message per <em>person</em>. Claiming only the finer grain
 * lets five ECS tasks split one person's expenses between them and each send its
 * own partial digest with a wrong count. {@code UNIQUE (useruuid, week_key)}
 * makes exactly one task the sender for a given person and week.
 */
@Entity
@Table(name = "expense_employee_digest_claim")
public class ExpenseEmployeeDigestClaim extends PanacheEntityBase {

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
