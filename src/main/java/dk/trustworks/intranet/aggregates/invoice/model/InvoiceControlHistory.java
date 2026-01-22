package dk.trustworks.intranet.aggregates.invoice.model;

import dk.trustworks.intranet.aggregates.invoice.model.enums.InvoiceControlStatus;
import io.quarkus.hibernate.orm.panache.PanacheEntityBase;
import jakarta.persistence.*;

import java.time.LocalDateTime;
import java.util.List;

/**
 * Invoice Control History Entity
 * <p>
 * Tracks all status changes for invoice control workflow.
 * Provides audit trail and timeline data for the invoice-controlling-admin page.
 * </p>
 *
 * @see Invoice
 * @see InvoiceControlStatus
 */
@Entity
@Table(name = "invoice_control_history")
public class InvoiceControlHistory extends PanacheEntityBase {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    public Long id;

    @Column(name = "invoice_uuid", nullable = false)
    public String invoiceUuid;

    @Enumerated(EnumType.STRING)
    @Column(name = "control_status", nullable = false, length = 20)
    public InvoiceControlStatus controlStatus;

    @Column(name = "control_note", columnDefinition = "TEXT")
    public String controlNote;

    @Column(name = "changed_at", nullable = false)
    public LocalDateTime changedAt;

    @Column(name = "changed_by", nullable = false)
    public String changedBy;

    /**
     * Default constructor for JPA
     */
    public InvoiceControlHistory() {
    }

    /**
     * Constructor for creating a new history entry
     *
     * @param invoiceUuid  Invoice UUID
     * @param controlStatus Control status
     * @param controlNote   Optional note
     * @param changedBy     User UUID who made the change
     */
    public InvoiceControlHistory(String invoiceUuid, InvoiceControlStatus controlStatus,
                                  String controlNote, String changedBy) {
        this.invoiceUuid = invoiceUuid;
        this.controlStatus = controlStatus;
        this.controlNote = controlNote;
        this.changedAt = LocalDateTime.now();
        this.changedBy = changedBy;
    }

    /**
     * Find all history entries for an invoice, ordered by changed_at descending
     *
     * @param invoiceUuid Invoice UUID
     * @return List of history entries (newest first)
     */
    public static List<InvoiceControlHistory> findByInvoiceUuid(String invoiceUuid) {
        return list("invoiceUuid = ?1 ORDER BY changedAt DESC", invoiceUuid);
    }

    /**
     * Count history entries for an invoice
     *
     * @param invoiceUuid Invoice UUID
     * @return Number of history entries
     */
    public static long countByInvoiceUuid(String invoiceUuid) {
        return count("invoiceUuid", invoiceUuid);
    }

    /**
     * Delete all history entries for an invoice (used when invoice is deleted)
     *
     * @param invoiceUuid Invoice UUID
     * @return Number of deleted entries
     */
    public static long deleteByInvoiceUuid(String invoiceUuid) {
        return delete("invoiceUuid", invoiceUuid);
    }
}
