package dk.trustworks.intranet.aggregates.invoice.model;

import com.fasterxml.jackson.annotation.JsonIgnore;
import io.quarkus.hibernate.orm.panache.PanacheEntityBase;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.IdClass;
import jakarta.persistence.PrePersist;
import jakarta.persistence.Table;
import lombok.AllArgsConstructor;
import lombok.EqualsAndHashCode;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.io.Serializable;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneOffset;

@Getter @Setter @Entity
@IdClass(PracticeInvoiceItemDeliverySource.Key.class)
@Table(name = "practice_invoice_item_delivery_source")
public class PracticeInvoiceItemDeliverySource extends PanacheEntityBase {
    @Id @Column(name = "invoice_item_uuid") public String invoiceItemUuid;
    @Id @Column(name = "work_uuid") public String workUuid;
    @JsonIgnore @Column(name = "registrant_uuid") public String registrantUuid;
    @JsonIgnore @Column(name = "effective_consultant_uuid") public String effectiveConsultantUuid;
    @Column(name = "delivery_date") public LocalDate deliveryDate;
    @Column(name = "task_uuid") public String taskUuid;
    @Column(name = "project_uuid") public String projectUuid;
    @Column(name = "contract_uuid") public String contractUuid;
    @Column(name = "contract_project_uuid") public String contractProjectUuid;
    @Column(name = "contract_consultant_uuid") public String contractConsultantUuid;
    @Column(name = "normalized_duration", precision = 24, scale = 6) public BigDecimal normalizedDuration;
    @Column(name = "normalized_rate", precision = 24, scale = 6) public BigDecimal normalizedRate;
    @Column(name = "delivery_value", precision = 48, scale = 12) public BigDecimal deliveryValue;
    @Column(name = "rate_resolution_status") public String rateResolutionStatus;
    @Column(name = "contribution_algorithm_version") public String contributionAlgorithmVersion;
    @Column(name = "item_fingerprint") public String itemFingerprint;
    @Column(name = "distribution_fingerprint") public String distributionFingerprint;
    @Column(name = "created_at", nullable = false, updatable = false)
    public LocalDateTime createdAt;

    @PrePersist
    void initializeCreatedAt() {
        if (createdAt == null) createdAt = LocalDateTime.now(ZoneOffset.UTC);
    }

    @NoArgsConstructor @AllArgsConstructor @EqualsAndHashCode
    public static class Key implements Serializable {
        public String invoiceItemUuid;
        public String workUuid;
    }
}
