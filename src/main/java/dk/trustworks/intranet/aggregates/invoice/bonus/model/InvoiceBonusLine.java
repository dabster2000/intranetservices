// src/main/java/dk/trustworks/intranet/aggregates/invoice/bonus/model/InvoiceBonusLine.java
package dk.trustworks.intranet.aggregates.invoice.bonus.model;

import io.quarkus.hibernate.orm.panache.PanacheEntityBase;
import jakarta.persistence.*;
import lombok.Getter; import lombok.Setter;
import org.eclipse.microprofile.openapi.annotations.media.Schema;

import java.time.LocalDateTime;
import java.util.UUID;

@Getter @Setter
@Entity
@Table(name = "invoice_bonus_lines",
       uniqueConstraints = @UniqueConstraint(name="ux_invbonusline_bonus_item",
               columnNames = {"bonusuuid","invoiceitemuuid"}))
@Schema(name="InvoiceBonusLine",
        description="Valgt fakturalinje og procent for en bonusansøgning")
public class InvoiceBonusLine extends PanacheEntityBase {

    @Id
    public String uuid;

    @Column(name="bonusuuid", nullable=false, length=36)
    public String bonusuuid;

    @Column(name="invoiceuuid", nullable=false, length=40)
    public String invoiceuuid;

    @Column(name="invoiceitemuuid", nullable=false, length=36)
    public String invoiceitemuuid;

    /** 0..100 */
    @Column(name="percentage", nullable=false)
    public double percentage;

    @Column(name="created_at", nullable=false)
    public LocalDateTime createdAt;

    @Column(name="updated_at", nullable=false)
    public LocalDateTime updatedAt;

    @PrePersist
    public void prePersist() {
        if (uuid == null) uuid = UUID.randomUUID().toString();
        createdAt = LocalDateTime.now();
        updatedAt = createdAt;
    }

    @PreUpdate
    public void preUpdate() { updatedAt = LocalDateTime.now(); }
}
