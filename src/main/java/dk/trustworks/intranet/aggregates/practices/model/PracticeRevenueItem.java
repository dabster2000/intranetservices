package dk.trustworks.intranet.aggregates.practices.model;

import io.quarkus.hibernate.orm.panache.PanacheEntityBase;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.IdClass;
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
@IdClass(PracticeRevenueItem.Key.class)
@Table(name = "fact_practice_net_revenue_item_mat")
public class PracticeRevenueItem extends PanacheEntityBase {

    @Id @Column(name = "generation_id") public String generationId;
    @Id @Column(name = "item_control_key") public String itemControlKey;
    @Column(name = "row_kind") public String rowKind;
    @Column(name = "source_document_uuid") public String sourceDocumentUuid;
    @Column(name = "source_item_uuid") public String sourceItemUuid;
    @Column(name = "company_uuid") public String companyUuid;
    @Column(name = "source_document_type") public String sourceDocumentType;
    @Column(name = "source_document_status") public String sourceDocumentStatus;
    @Column(name = "recognized_month") public LocalDate recognizedMonth;
    @Column(name = "attribution_period_start") public LocalDate attributionPeriodStart;
    @Column(name = "attribution_period_end") public LocalDate attributionPeriodEnd;
    @Column(name = "source_credit_document_uuid") public String sourceCreditDocumentUuid;
    @Column(name = "source_credit_item_uuid") public String sourceCreditItemUuid;
    @Column(name = "source_credit_attribution_uuid") public String sourceCreditAttributionUuid;
    @Column(name = "item_category") public String itemCategory;
    @Column(name = "adjustment_subtype") public String adjustmentSubtype;
    @Column(name = "native_currency") public String nativeCurrency;
    @Column(name = "native_item_amount", precision = 48, scale = 12) public BigDecimal nativeItemAmount;
    @Column(name = "document_sign") public Short documentSign;
    @Column(name = "signed_native_control", precision = 48, scale = 12) public BigDecimal signedNativeControl;
    @Column(name = "item_control_dkk", precision = 48, scale = 12) public BigDecimal itemControlDkk;
    @Column(name = "document_control_dkk", precision = 48, scale = 2) public BigDecimal documentControlDkk;
    @Column(name = "document_gl_revenue_dkk", precision = 48, scale = 4) public BigDecimal documentGlRevenueDkk;
    @Column(name = "item_cent_adjustment_dkk", precision = 48, scale = 2) public BigDecimal itemCentAdjustmentDkk;
    @Column(name = "effective_document_ratio", precision = 38, scale = 18) public BigDecimal effectiveDocumentRatio;
    @Column(name = "document_ratio_closure_row") public boolean documentRatioClosureRow;
    @Column(name = "document_ratio_normalization_applied") public boolean documentRatioNormalizationApplied;
    @Column(name = "unrounded_item_dkk", precision = 65, scale = 20) public BigDecimal unroundedItemDkk;
    @Column(name = "provisional_document_control_dkk", precision = 48, scale = 2) public BigDecimal provisionalDocumentControlDkk;
    @Column(name = "dkk_per_native_unit", precision = 17, scale = 8) public BigDecimal dkkPerNativeUnit;
    @Column(name = "cent_floor_dkk", precision = 48, scale = 2) public BigDecimal centFloorDkk;
    @Column(name = "fractional_cent_residue", precision = 38, scale = 20) public BigDecimal fractionalCentResidue;
    @Column(name = "one_cent_awarded") public boolean oneCentAwarded;
    @Column(name = "fx_normalization_changed") public boolean fxNormalizationChanged;
    @Column(name = "matched_voucher_key") public String matchedVoucherKey;
    @Column(name = "matched_gl_raw_dkk", precision = 48, scale = 4) public BigDecimal matchedGlRawDkk;
    @Column(name = "matched_gl_candidate_cent_dkk", precision = 48, scale = 2) public BigDecimal matchedGlCandidateCentDkk;
    @Column(name = "matched_accounting_identifier") public String matchedAccountingIdentifier;
    @Column(name = "matched_accounting_namespace") public String matchedAccountingNamespace;
    @Column(name = "control_source") public String controlSource;
    @Column(name = "valuation_status") public String valuationStatus;
    @Column(name = "residual_control_reason") public String residualControlReason;
    @Column(name = "attribution_source_status") public String attributionSourceStatus;
    @Column(name = "evidence_resolved_segment") public String evidenceResolvedSegment;
    @Column(name = "evidence_practice_basis") public String evidencePracticeBasis;
    @Column(name = "evidence_consultant_type_basis") public String evidenceConsultantTypeBasis;
    @Column(name = "scope_resolution_status") public String scopeResolutionStatus;
    @Column(name = "scope_resolution_reason") public String scopeResolutionReason;
    @Column(name = "copy_provenance") public String copyProvenance;
    @Column(name = "source_distribution_fingerprint") public String sourceDistributionFingerprint;
    @Column(name = "dependency_fingerprint") public String dependencyFingerprint;
    @Column(name = "pricing_policy_version") public String pricingPolicyVersion;
    @Column(name = "pricing_step_id") public String pricingStepId;
    @Column(name = "pricing_step_sequence") public Integer pricingStepSequence;
    @Column(name = "pricing_rule_type") public String pricingRuleType;
    @Column(name = "pricing_input_fingerprint") public String pricingInputFingerprint;
    @Column(name = "pricing_output_fingerprint") public String pricingOutputFingerprint;
    @Column(name = "pricing_output_amount", precision = 48, scale = 12) public BigDecimal pricingOutputAmount;
    @Column(name = "calculation_algorithm_version") public String calculationAlgorithmVersion;
    @Column(name = "credit_copy_kind") public String creditCopyKind;
    @Column(name = "credit_copy_scope") public String creditCopyScope;
    @Column(name = "credit_copy_scale", precision = 38, scale = 18) public BigDecimal creditCopyScale;
    @Column(name = "credit_copy_original_source_native_amount", precision = 48, scale = 12) public BigDecimal creditCopyOriginalSourceNativeAmount;
    @Column(name = "credit_copy_fingerprint") public String creditCopyFingerprint;
    @Column(name = "duplicate_risk_status") public String duplicateRiskStatus;
    @Column(name = "synthetic_residual") public boolean syntheticResidual;
    @Column(name = "source_fingerprint") public String sourceFingerprint;
    @Column(name = "validation_reason_code") public String validationReasonCode;
    @Column(name = "created_at") public LocalDateTime createdAt;
    @Column(name = "refreshed_at") public LocalDateTime refreshedAt;

    @NoArgsConstructor @AllArgsConstructor @EqualsAndHashCode
    public static class Key implements Serializable {
        public String generationId;
        public String itemControlKey;
    }
}
