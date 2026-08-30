package dk.trustworks.intranet.expenseservice.model;

import io.quarkus.hibernate.orm.panache.PanacheEntityBase;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.time.LocalDateTime;

/**
 * One "we told the employee" record (V541).
 *
 * <p>The {@code (expense_uuid, episode_at, reminder_seq)} unique key is the
 * atomic cross-instance claim, not decoration: production runs up to five ECS
 * tasks, so a SELECT-then-INSERT dedupe would race. The insert IS the claim —
 * a constraint violation means another task already sent this DM.
 *
 * <p>{@code episodeAt} is the {@code occurred_at} of the decision-log row that
 * handed the expense to the employee. See the migration header for why
 * {@code expenses.attention_since} cannot serve that role.
 */
@Entity
@Table(name = "expense_employee_notification")
public class ExpenseEmployeeNotification extends PanacheEntityBase {

    /** {@link #reminderSeq} value for the DM sent the moment the expense lands on the employee. */
    public static final int SEQ_INITIAL = 0;

    @Id
    public String uuid;

    @Column(name = "expense_uuid", nullable = false)
    public String expenseUuid;

    @Column(name = "episode_at", nullable = false)
    public LocalDateTime episodeAt;

    @Column(name = "notified_at", nullable = false)
    public LocalDateTime notifiedAt;

    @Column(name = "channel", nullable = false)
    public String channel;

    /**
     * 0 for the initial DM; 1, 2, … for successive weekly reminders about the
     * same episode. Part of the unique key so a reminder is claimed
     * independently of the initial DM while still being idempotent per week.
     */
    @Column(name = "reminder_seq", nullable = false)
    public int reminderSeq;
}
