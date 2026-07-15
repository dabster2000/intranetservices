package dk.trustworks.intranet.aggregates.practices.model;

import com.fasterxml.jackson.annotation.JsonIgnore;
import io.quarkus.hibernate.orm.panache.PanacheEntityBase;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.IdClass;
import jakarta.persistence.Lob;
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

@Getter
@Setter
@Entity
@IdClass(PracticeRevenueAllocation.Key.class)
@Table(name = "fact_practice_net_revenue_allocation_mat")
public class PracticeRevenueAllocation extends PanacheEntityBase {
    @Id @Column(name = "generation_id") public String generationId;
    @Id @Column(name = "item_control_key") public String itemControlKey;
    @Id @Column(name = "allocation_sequence") public int allocationSequence;
    @JsonIgnore @Column(name = "consultant_uuid") public String consultantUuid;
    @Column(name = "segment_id") public String segmentId;
    @Column(name = "effective_practice_code") public String effectivePracticeCode;
    @Column(name = "effective_practice_basis") public String effectivePracticeBasis;
    @Column(name = "practice_resolution_method") public String practiceResolutionMethod;
    @Column(name = "inherited_credit_resolution") public boolean inheritedCreditResolution;
    @Column(name = "source_allocation_reference") public String sourceAllocationReference;
    @Column(name = "source_dependency_reference") public String sourceDependencyReference;
    @Column(name = "attribution_source") public String attributionSource;
    @Column(name = "attribution_status") public String attributionStatus;
    @Column(name = "share_before_rounding", precision = 38, scale = 18) public BigDecimal shareBeforeRounding;
    @Column(name = "raw_fraction", precision = 38, scale = 18) public BigDecimal rawFraction;
    @Column(name = "effective_normalized_fraction", precision = 38, scale = 18) public BigDecimal effectiveNormalizedFraction;
    @Column(name = "raw_share_sum", precision = 38, scale = 18) public BigDecimal rawShareSum;
    @Column(name = "fraction_closure_row") public boolean fractionClosureRow;
    @Column(name = "fraction_normalization_applied") public boolean fractionNormalizationApplied;
    @Lob @Column(name = "contributing_source_ids") public String contributingSourceIds;
    @Column(name = "unrounded_allocation_dkk", precision = 65, scale = 20) public BigDecimal unroundedAllocationDkk;
    @Column(name = "floor_allocation_dkk", precision = 48, scale = 2) public BigDecimal floorAllocationDkk;
    @Column(name = "fractional_cent_residue", precision = 38, scale = 20) public BigDecimal fractionalCentResidue;
    @Column(name = "one_cent_awarded") public boolean oneCentAwarded;
    @Column(name = "allocation_dkk", precision = 48, scale = 2) public BigDecimal allocationDkk;
    @Column(name = "delivery_start_date") public LocalDate deliveryStartDate;
    @Column(name = "delivery_end_date") public LocalDate deliveryEndDate;
    @Column(name = "historical_practice_fallback") public boolean historicalPracticeFallback;
    @Column(name = "residual_reason") public String residualReason;
    @Column(name = "created_at") public LocalDateTime createdAt;
    @Column(name = "refreshed_at") public LocalDateTime refreshedAt;

    @NoArgsConstructor @AllArgsConstructor @EqualsAndHashCode
    public static class Key implements Serializable {
        public String generationId;
        public String itemControlKey;
        public int allocationSequence;
    }
}
