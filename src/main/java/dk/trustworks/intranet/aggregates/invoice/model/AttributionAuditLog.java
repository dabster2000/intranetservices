package dk.trustworks.intranet.aggregates.invoice.model;

import io.quarkus.hibernate.orm.panache.PanacheEntityBase;
import jakarta.persistence.*;
import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;

@Entity
@Table(name = "attribution_audit_log")
public class AttributionAuditLog extends PanacheEntityBase {

    @Id
    public String uuid;

    @Column(name = "invoice_uuid", nullable = false)
    public String invoiceUuid;

    @Column(name = "item_uuid", nullable = false)
    public String itemUuid;

    @Column(name = "changed_by", nullable = false)
    public String changedBy;

    @Column(name = "change_type", nullable = false)
    public String changeType;

    @Column(name = "old_state", columnDefinition = "JSON", nullable = false)
    public String oldState;

    @Column(name = "new_state", columnDefinition = "JSON", nullable = false)
    public String newState;

    @Column(name = "reason")
    public String reason;

    @Column(name = "created_at", nullable = false, updatable = false)
    public LocalDateTime createdAt;

    public AttributionAuditLog() {}

    public AttributionAuditLog(String invoiceUuid, String itemUuid, String changedBy,
                                String changeType, String oldState, String newState, String reason) {
        this.uuid = UUID.randomUUID().toString();
        this.invoiceUuid = invoiceUuid;
        this.itemUuid = itemUuid;
        this.changedBy = changedBy;
        this.changeType = changeType;
        this.oldState = oldState;
        this.newState = newState;
        this.reason = reason;
        this.createdAt = LocalDateTime.now();
    }

    public static List<AttributionAuditLog> findByInvoice(String invoiceUuid) {
        return list("invoiceUuid", invoiceUuid);
    }

    public static List<AttributionAuditLog> findByItem(String itemUuid) {
        return list("itemUuid", itemUuid);
    }
}
