package dk.trustworks.intranet.aggregates.clientstatus.model;

import io.quarkus.hibernate.orm.panache.PanacheEntityBase;
import jakarta.persistence.*;
import lombok.Getter;
import lombok.Setter;
import org.eclipse.microprofile.openapi.annotations.media.Schema;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;

/**
 * Append-only audit trail for {@link ClientMonthControl}: one row per change, capturing the action,
 * who made it, when, and the approval snapshot values at that moment. Never mutated after insert.
 */
@Getter
@Setter
@Entity
@Table(name = "client_month_control_history")
@Schema(name = "ClientMonthControlHistory",
        description = "One audit entry for a change to a client-month controlling cell.")
public class ClientMonthControlHistory extends PanacheEntityBase {

    /** The set of actions recorded in {@link #action}. */
    public enum Action {APPROVED, REAPPROVED, UNAPPROVED, NOTE_UPDATED, BULK_APPROVED}

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    public Long id;

    @Column(name = "control_uuid", nullable = false, length = 36)
    public String controlUuid;

    @Column(name = "client_uuid", nullable = false, length = 36)
    public String clientUuid;

    @Column(name = "month", nullable = false)
    public LocalDate month;

    @Column(name = "action", nullable = false, length = 30)
    public String action;

    @Column(name = "note", columnDefinition = "TEXT")
    public String note;

    @Column(name = "approved_expected")
    public Double approvedExpected;

    @Column(name = "approved_invoiced")
    public Double approvedInvoiced;

    @Column(name = "changed_at", nullable = false)
    public LocalDateTime changedAt;

    @Column(name = "changed_by", length = 36)
    public String changedBy;

    public ClientMonthControlHistory() {
    }

    public ClientMonthControlHistory(String controlUuid, String clientUuid, LocalDate month,
                                     Action action, String note,
                                     Double approvedExpected, Double approvedInvoiced,
                                     String changedBy) {
        this.controlUuid = controlUuid;
        this.clientUuid = clientUuid;
        this.month = month;
        this.action = action.name();
        this.note = note;
        this.approvedExpected = approvedExpected;
        this.approvedInvoiced = approvedInvoiced;
        this.changedAt = LocalDateTime.now(java.time.ZoneOffset.UTC);
        this.changedBy = changedBy;
    }

    public static List<ClientMonthControlHistory> findByClientAndMonth(String clientUuid, LocalDate month) {
        return list("clientUuid = ?1 and month = ?2 ORDER BY changedAt DESC", clientUuid, month);
    }
}
