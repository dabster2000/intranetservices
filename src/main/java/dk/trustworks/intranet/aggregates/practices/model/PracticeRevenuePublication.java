package dk.trustworks.intranet.aggregates.practices.model;

import io.quarkus.hibernate.orm.panache.PanacheEntityBase;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.Version;
import lombok.Getter;
import lombok.Setter;

import java.math.BigDecimal;
import java.math.BigInteger;
import java.time.LocalDate;
import java.time.LocalDateTime;

@Getter
@Setter
@Entity
@Table(name = "practice_revenue_publication")
public class PracticeRevenuePublication extends PanacheEntityBase {
    @Id @Column(name = "publication_key") public String publicationKey;
    @Column(name = "published_generation_id") public String publishedGenerationId;
    @Column(name = "previous_generation_id") public String previousGenerationId;
    @Column(name = "attempt_generation_id") public String attemptGenerationId;
    @Column(name = "paired_cost_generation_at") public LocalDateTime pairedCostGenerationAt;
    @Column(name = "practice_basis_generation_id") public String practiceBasisGenerationId;
    @Column(name = "full_bi_refresh_version", columnDefinition = "BIGINT UNSIGNED") public BigInteger fullBiRefreshVersion;
    @Column(name = "invoice_document_source_version", columnDefinition = "BIGINT UNSIGNED") public BigInteger invoiceDocumentSourceVersion;
    @Column(name = "finance_gl_source_version", columnDefinition = "BIGINT UNSIGNED") public BigInteger financeGlSourceVersion;
    @Column(name = "currency_source_version", columnDefinition = "BIGINT UNSIGNED") public BigInteger currencySourceVersion;
    @Column(name = "account_classification_source_version", columnDefinition = "BIGINT UNSIGNED") public BigInteger accountClassificationSourceVersion;
    @Column(name = "invoice_attribution_source_version", columnDefinition = "BIGINT UNSIGNED") public BigInteger invoiceAttributionSourceVersion;
    @Column(name = "self_billed_source_version", columnDefinition = "BIGINT UNSIGNED") public BigInteger selfBilledSourceVersion;
    @Column(name = "phantom_attribution_source_version", columnDefinition = "BIGINT UNSIGNED") public BigInteger phantomAttributionSourceVersion;
    @Column(name = "delivery_evidence_source_version", columnDefinition = "BIGINT UNSIGNED") public BigInteger deliveryEvidenceSourceVersion;
    @Column(name = "practice_basis_input_source_version", columnDefinition = "BIGINT UNSIGNED") public BigInteger practiceBasisInputSourceVersion;
    @Column(name = "booked_available") public boolean bookedAvailable;
    @Column(name = "booked_reason") public String bookedReason;
    @Column(name = "booked_anchor_month") public LocalDate bookedAnchorMonth;
    @Column(name = "booked_current_start_month") public LocalDate bookedCurrentStartMonth;
    @Column(name = "booked_current_end_month") public LocalDate bookedCurrentEndMonth;
    @Column(name = "booked_prior_start_month") public LocalDate bookedPriorStartMonth;
    @Column(name = "booked_prior_end_month") public LocalDate bookedPriorEndMonth;
    @Column(name = "booked_plus_draft_available") public boolean bookedPlusDraftAvailable;
    @Column(name = "booked_plus_draft_reason") public String bookedPlusDraftReason;
    @Column(name = "booked_plus_draft_anchor_month") public LocalDate bookedPlusDraftAnchorMonth;
    @Column(name = "booked_plus_draft_current_start_month") public LocalDate bookedPlusDraftCurrentStartMonth;
    @Column(name = "booked_plus_draft_current_end_month") public LocalDate bookedPlusDraftCurrentEndMonth;
    @Column(name = "booked_plus_draft_prior_start_month") public LocalDate bookedPlusDraftPriorStartMonth;
    @Column(name = "booked_plus_draft_prior_end_month") public LocalDate bookedPlusDraftPriorEndMonth;
    public String status;
    @Column(name = "shared_control_version", columnDefinition = "BIGINT UNSIGNED") public BigInteger sharedControlVersion;
    @Column(name = "owner_token") public String ownerToken;
    @Column(name = "lock_acquired_at") public LocalDateTime lockAcquiredAt;
    @Column(name = "source_snapshot_at") public LocalDateTime sourceSnapshotAt;
    @Column(name = "source_snapshot_fact_change_log_high_water", columnDefinition = "BIGINT UNSIGNED") public BigInteger sourceSnapshotFactChangeLogHighWater;
    @Column(name = "coverage_start_month") public LocalDate coverageStartMonth;
    @Column(name = "coverage_end_month") public LocalDate coverageEndMonth;
    @Column(name = "source_document_count", columnDefinition = "BIGINT UNSIGNED") public BigInteger sourceDocumentCount;
    @Column(name = "source_item_count", columnDefinition = "BIGINT UNSIGNED") public BigInteger sourceItemCount;
    @Column(name = "valued_item_count", columnDefinition = "BIGINT UNSIGNED") public BigInteger valuedItemCount;
    @Column(name = "allocation_count", columnDefinition = "BIGINT UNSIGNED") public BigInteger allocationCount;
    @Column(name = "missing_control_count", columnDefinition = "BIGINT UNSIGNED") public BigInteger missingControlCount;
    @Column(name = "provisional_control_count", columnDefinition = "BIGINT UNSIGNED") public BigInteger provisionalControlCount;
    @Column(name = "confirmed_attribution_count", columnDefinition = "BIGINT UNSIGNED") public BigInteger confirmedAttributionCount;
    @Column(name = "estimated_attribution_count", columnDefinition = "BIGINT UNSIGNED") public BigInteger estimatedAttributionCount;
    @Column(name = "unassigned_allocation_count", columnDefinition = "BIGINT UNSIGNED") public BigInteger unassignedAllocationCount;
    @Column(name = "residual_control_count", columnDefinition = "BIGINT UNSIGNED") public BigInteger residualControlCount;
    @Column(name = "duplicate_risk_count", columnDefinition = "BIGINT UNSIGNED") public BigInteger duplicateRiskCount;
    @Column(name = "item_control_total_dkk", precision = 48, scale = 2) public BigDecimal itemControlTotalDkk;
    @Column(name = "allocation_total_dkk", precision = 48, scale = 2) public BigDecimal allocationTotalDkk;
    @Column(name = "gl_control_total_dkk", precision = 48, scale = 2) public BigDecimal glControlTotalDkk;
    @Column(name = "reconciliation_gap_dkk", precision = 48, scale = 2) public BigDecimal reconciliationGapDkk;
    @Column(name = "started_at") public LocalDateTime startedAt;
    @Column(name = "published_at") public LocalDateTime publishedAt;
    @Column(name = "failed_at") public LocalDateTime failedAt;
    @Column(name = "refreshed_at") public LocalDateTime refreshedAt;
    @Column(name = "failure_code") public String failureCode;
    // Long, not BigInteger, despite the BIGINT UNSIGNED columns above: Hibernate accepts only
    // int/Integer/short/Short/long/Long/Timestamp as a @Version type and dies at boot otherwise.
    @Version @Column(name = "publication_version", columnDefinition = "BIGINT UNSIGNED") public Long publicationVersion;
}
