package dk.trustworks.intranet.aggregates.invoice.model;

import com.fasterxml.jackson.annotation.JsonIgnore;
import dk.trustworks.intranet.aggregates.invoice.model.enums.AttributionSource;
import io.quarkus.hibernate.orm.panache.PanacheEntityBase;
import jakarta.persistence.*;
import lombok.Getter;
import lombok.Setter;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDateTime;
import java.util.UUID;

@Getter
@Setter
@Entity
@Table(name = "invoice_item_attributions")
public class InvoiceItemAttribution extends PanacheEntityBase {

    @Id
    public String uuid;

    @Column(name = "invoiceitem_uuid")
    public String invoiceitemUuid;

    @Column(name = "consultant_uuid")
    public String consultantUuid;

    @Column(name = "share_pct")
    public BigDecimal sharePct;

    @Column(name = "attributed_amount")
    public BigDecimal attributedAmount;

    @Column(name = "original_hours")
    public BigDecimal originalHours;

    @Enumerated(EnumType.STRING)
    public AttributionSource source;

    @Column(name = "created_at", updatable = false)
    public LocalDateTime createdAt;

    @Column(name = "updated_at")
    public LocalDateTime updatedAt;

    @JsonIgnore
    @Column(name = "source_item_uuid")
    public String sourceItemUuid;

    @JsonIgnore
    @Column(name = "source_attribution_uuid")
    public String sourceAttributionUuid;

    @JsonIgnore
    @Column(name = "copy_provenance")
    public String copyProvenance;

    @JsonIgnore
    @Column(name = "source_distribution_fingerprint")
    public String sourceDistributionFingerprint;

    @JsonIgnore
    @Column(name = "attribution_algorithm_version")
    public String attributionAlgorithmVersion;

    @JsonIgnore
    @Column(name = "attribution_source_kind")
    public String attributionSourceKind;

    @JsonIgnore
    @Column(name = "attribution_dependency_fingerprint")
    public String attributionDependencyFingerprint;

    @Transient
    public String consultantName;

    public InvoiceItemAttribution() {
    }

    public InvoiceItemAttribution(String invoiceitemUuid,
                                  String consultantUuid,
                                  BigDecimal sharePct,
                                  BigDecimal attributedAmount,
                                  BigDecimal originalHours,
                                  AttributionSource source) {
        this.uuid = UUID.randomUUID().toString();
        this.invoiceitemUuid = invoiceitemUuid;
        this.consultantUuid = consultantUuid;
        this.sharePct = sharePct;
        this.attributedAmount = attributedAmount;
        this.originalHours = originalHours;
        this.source = source;
        this.createdAt = LocalDateTime.now();
        this.updatedAt = LocalDateTime.now();
    }

    /**
     * Recalculates attributedAmount as sharePct% of itemTotal.
     * Uses HALF_UP rounding, scale 2.
     *
     * @param itemTotal the total value of the invoice item
     */
    public void recalculateAmount(double itemTotal) {
        BigDecimal total = BigDecimal.valueOf(itemTotal);
        this.attributedAmount = sharePct
                .multiply(total)
                .divide(BigDecimal.valueOf(100), 2, RoundingMode.HALF_UP);
    }
}
