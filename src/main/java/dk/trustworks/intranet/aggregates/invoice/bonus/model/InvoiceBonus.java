package dk.trustworks.intranet.aggregates.invoice.bonus.model;

import dk.trustworks.intranet.model.enums.SalesApprovalStatus;
import io.quarkus.hibernate.orm.panache.PanacheEntityBase;
import jakarta.persistence.*;
import lombok.Getter; import lombok.Setter;

import java.time.LocalDateTime;
import java.util.UUID;

import org.eclipse.microprofile.openapi.annotations.media.Schema;

@Getter @Setter
@Entity
@Table(name = "invoice_bonuses",
        uniqueConstraints = @UniqueConstraint(name="ux_invoice_bonuses_invoice_user",
                columnNames = {"invoiceuuid","useruuid"}))
@Schema(
        name = "InvoiceBonus",
        description = "Bonusrække knyttet til en faktura og en bruger. `computedAmount` beregnes server-side ud fra fakturabeløb og `shareType`/`shareValue`."
)
public class InvoiceBonus extends PanacheEntityBase {

    @Id
    @Schema(description = "Bonus UUID", example = "33333333-3333-3333-3333-333333333333", readOnly = true)
    public String uuid;

    @Column(name="invoiceuuid", nullable=false, length=40)
    @Schema(description = "Faktura UUID", example = "2b0d9fbe-6f1a-4a17-9f8c-8a8a8a8a8a8a")
    public String invoiceuuid;

    @Column(name="useruuid", nullable=false, length=36)
    @Schema(description = "Bruger UUID (modtager af bonus)", example = "11111111-1111-1111-1111-111111111111")
    public String useruuid;

    @Enumerated(EnumType.STRING)
    @Column(name="share_type", nullable=false, length=16)
    @Schema(
            description = "Andelstype: procent (0-100) eller fast beløb i fakturaens valuta",
            enumeration = {"PERCENT","AMOUNT"}
    )
    public ShareType shareType; // PERCENT eller AMOUNT

    @Column(name="share_value", nullable=false)
    @Schema(
            description = "Hvis PERCENT: værdi i [0;100]. Hvis AMOUNT: fast beløb i fakturaens valuta.",
            example = "10.0"
    )
    public double shareValue;

    @Column(name="computed_amount", nullable=false)
    @Schema(
            description = "Udregnet bonusbeløb (server-side). Rundes til 2 decimaler.",
            example = "2500.00",
            readOnly = true
    )
    public double computedAmount;

    @Enumerated(EnumType.STRING)
    @Column(name="status", nullable=false, length=16)
    @Schema(
            description = "Godkendelsesstatus",
            implementation = SalesApprovalStatus.class,
            enumeration = {"PENDING","APPROVED","REJECTED"}
    )
    public SalesApprovalStatus status = SalesApprovalStatus.PENDING;

    @Column(name="override_note", columnDefinition = "text")
    @Schema(description = "Valgfri note/kommentar", example = "Fast bonus jf. aftale")
    public String overrideNote;

    @Column(name="added_by", nullable=false, length=36)
    @Schema(description = "UUID for den bruger som oprettede rækken", example = "22222222-2222-2222-2222-222222222222")
    public String addedBy; // hvem tilføjede rækken

    @Column(name="approved_by", length=36)
    @Schema(description = "UUID for godkender (hvis godkendt/afvist)", example = "44444444-4444-4444-4444-444444444444")
    public String approvedBy;

    @Column(name="created_at", nullable=false)
    @Schema(description = "Oprettet tidspunkt (server-tid)", readOnly = true)
    public LocalDateTime createdAt;

    @Column(name="updated_at", nullable=false)
    @Schema(description = "Sidst opdateret (server-tid)", readOnly = true)
    public LocalDateTime updatedAt;

    @Column(name="approved_at")
    @Schema(description = "Godkendelsestidspunkt (hvis godkendt/afvist)", readOnly = true)
    public LocalDateTime approvedAt;

    @PrePersist
    public void prePersist() {
        if (uuid == null) uuid = UUID.randomUUID().toString();
        createdAt = LocalDateTime.now();
        updatedAt = createdAt;
        if (status == null) status = SalesApprovalStatus.PENDING;
    }

    @PreUpdate
    public void preUpdate() { updatedAt = LocalDateTime.now(); }

    @Schema(description = "Andelstype", enumeration = {"PERCENT","AMOUNT"})
    public enum ShareType { PERCENT, AMOUNT }
}
