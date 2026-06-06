package dk.trustworks.intranet.aggregates.invoice.model;

import io.quarkus.hibernate.orm.panache.PanacheEntityBase;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.UUID;

/**
 * Auditable link between an issued INTERNAL/credit-note invoice and each PHANTOM
 * it settled, with the phantom's contribution captured at issue time. Lets a later
 * phantom credit note or internal reversal reason about what was already settled
 * (re-settlement deltas are computed against {@code attributedAmountAtIssue},
 * not re-derived amounts — avoids drift).
 */
@Entity
@Table(name = "internal_invoice_phantom_link")
public class InternalInvoicePhantomLink extends PanacheEntityBase {

    @Id
    @Column(name = "uuid", length = 36)
    public String uuid;

    @Column(name = "internal_uuid", length = 36)
    public String internalUuid;

    @Column(name = "phantom_uuid", length = 36)
    public String phantomUuid;

    @Column(name = "attributed_amount_at_issue")
    public BigDecimal attributedAmountAtIssue;

    @Column(name = "created_at")
    public LocalDateTime createdAt;

    public InternalInvoicePhantomLink() {
    }

    public InternalInvoicePhantomLink(String internalUuid, String phantomUuid, BigDecimal attributedAmountAtIssue) {
        this.uuid = UUID.randomUUID().toString();
        this.internalUuid = internalUuid;
        this.phantomUuid = phantomUuid;
        this.attributedAmountAtIssue = attributedAmountAtIssue;
        this.createdAt = LocalDateTime.now();
    }
}
