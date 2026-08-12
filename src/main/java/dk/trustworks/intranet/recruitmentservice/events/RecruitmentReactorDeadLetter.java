package dk.trustworks.intranet.recruitmentservice.events;

import io.quarkus.hibernate.orm.panache.PanacheEntityBase;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.IdClass;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.io.Serializable;
import java.time.LocalDateTime;
import java.util.Objects;

/**
 * An event a reactor permanently gave up on (V490).
 * <p>
 * Reactors that override {@link RecruitmentReactor#maxDeliveryAttempts()}
 * advance their watermark past an event that failed every attempt, so the
 * event is never delivered again. Before V490 the only trace was one ERROR
 * line: {@link RecruitmentReactor} did insert a {@code SKIPPED} row into
 * {@code recruitment_reactor_deliveries}, but the watermark advance on the
 * very next line pruned it again. On 2026-08-11 that silently discarded
 * three production Slack cards.
 * <p>
 * A row here is the durable, human-resolvable record instead — the
 * recruitment sibling of the {@code invoice_economics_uploads} ABANDONED
 * state (V478). It is written in its own committed transaction, is NEVER
 * pruned by the watermark, and keeps the reactor's dead-letter alarm red
 * until an operator replays or abandons it.
 * <p>
 * <b>PII boundary:</b> {@link #errorMessage} is reactor diagnostics —
 * exception text about channels, ids and transport codes. Candidate
 * personal data must never be routed into it.
 */
@Getter
@Setter
@NoArgsConstructor
@Entity
@Table(name = "recruitment_reactor_dead_letters")
@IdClass(RecruitmentReactorDeadLetter.Key.class)
public class RecruitmentReactorDeadLetter extends PanacheEntityBase {

    /** Needs a human: the side effect never happened. */
    public static final String STATUS_OPEN = "OPEN";
    /** Re-delivered successfully by an operator. */
    public static final String STATUS_REPLAYED = "REPLAYED";
    /** Deliberately closed without re-delivery (moot, or done by hand). */
    public static final String STATUS_ABANDONED = "ABANDONED";

    /** Matches the {@code error_message} column width. */
    static final int MESSAGE_MAX_CHARS = 1000;

    /** Matches the {@code error_class} column width. */
    static final int CLASS_MAX_CHARS = 255;

    @Id
    @Column(name = "reactor_name", length = 100, nullable = false, updatable = false)
    private String reactorName;

    @Id
    @Column(name = "event_seq", nullable = false, updatable = false)
    private Long eventSeq;

    @Column(name = "attempts", nullable = false)
    private int attempts;

    @Column(name = "error_class", length = CLASS_MAX_CHARS)
    private String errorClass;

    @Column(name = "error_message", length = MESSAGE_MAX_CHARS)
    private String errorMessage;

    @Column(name = "status", length = 12, nullable = false)
    private String status;

    /** UTC. */
    @Column(name = "dead_lettered_at", nullable = false, columnDefinition = "DATETIME(3)")
    private LocalDateTime deadLetteredAt;

    /** UTC. */
    @Column(name = "last_attempt_at", columnDefinition = "DATETIME(3)")
    private LocalDateTime lastAttemptAt;

    /** UTC. */
    @Column(name = "resolved_at", columnDefinition = "DATETIME(3)")
    private LocalDateTime resolvedAt;

    @Column(name = "resolved_by", length = 36)
    private String resolvedBy;

    /**
     * The root cause of a failed delivery, already trimmed to what the
     * columns can hold.
     *
     * @param errorClass   root-cause exception class name, null when unknown
     * @param errorMessage root-cause message, null when the exception had none
     */
    public record Cause(String errorClass, String errorMessage) {
    }

    /**
     * Unwraps a delivery failure to the cause worth storing.
     * <p>
     * Deliveries fail inside {@code QuarkusTransaction.requiringNew()}, so
     * the throwable that reaches the reactor is a
     * {@code QuarkusTransactionException} wrapping the handler's real
     * exception — the wrapper's own message ("java.io.IOException: ...") is
     * noise. This walks to the deepest cause and reports that, which is what
     * an operator needs to see: {@code java.io.IOException} /
     * {@code "Slack root-card post failed for channel C0BNQP76M1D:
     * channel_not_found"}.
     * <p>
     * Pure and null/cycle-tolerant: a self-referencing cause chain (some
     * drivers do this) terminates instead of spinning.
     */
    public static Cause describeCause(Throwable failure) {
        if (failure == null) {
            return new Cause(null, null);
        }
        Throwable root = failure;
        // Bounded as well as cycle-guarded: a chain longer than this is
        // pathological, and an operator gains nothing from unwrapping it.
        for (int depth = 0; depth < 32; depth++) {
            Throwable next = root.getCause();
            if (next == null || next == root) {
                break;
            }
            root = next;
        }
        return new Cause(
                truncate(root.getClass().getName(), CLASS_MAX_CHARS),
                truncate(root.getMessage(), MESSAGE_MAX_CHARS));
    }

    /** Null-safe truncation to a column width; null and short values pass through. */
    static String truncate(String value, int maxChars) {
        if (value == null || value.length() <= maxChars) {
            return value;
        }
        return value.substring(0, maxChars);
    }

    /** Composite primary key (reactor_name, event_seq). */
    public static class Key implements Serializable {
        private String reactorName;
        private Long eventSeq;

        public Key() {
        }

        public Key(String reactorName, Long eventSeq) {
            this.reactorName = reactorName;
            this.eventSeq = eventSeq;
        }

        @Override
        public boolean equals(Object o) {
            if (this == o) return true;
            if (!(o instanceof Key key)) return false;
            return Objects.equals(reactorName, key.reactorName) && Objects.equals(eventSeq, key.eventSeq);
        }

        @Override
        public int hashCode() {
            return Objects.hash(reactorName, eventSeq);
        }
    }
}
