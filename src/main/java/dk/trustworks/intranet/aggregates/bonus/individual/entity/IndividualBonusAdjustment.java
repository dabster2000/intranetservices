package dk.trustworks.intranet.aggregates.bonus.individual.entity;

import io.quarkus.hibernate.orm.panache.PanacheEntityBase;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.Version;
import lombok.Data;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;

@Data
@Entity
@Table(name = "individual_bonus_adjustment")
public class IndividualBonusAdjustment extends PanacheEntityBase {
    @Id @Column(name = "uuid", nullable = false, length = 36) private String uuid;
    @Column(name = "rule_uuid", nullable = false, length = 36) private String ruleUuid;
    @Column(name = "user_uuid", nullable = false, length = 36) private String userUuid;
    @Column(name = "company_uuid", length = 36) private String companyUuid;
    @Column(name = "earning_month", nullable = false) private LocalDate earningMonth;
    @Column(name = "original_payout_uuid", length = 36) private String originalPayoutUuid;
    @Column(name = "original_source_reference", length = 255) private String originalSourceReference;
    @Column(name = "revision", nullable = false) private Integer revision;
    @Column(name = "issue_type", nullable = false, length = 40) private String issueType;
    @Column(name = "state", nullable = false, length = 40) private String state;
    @Column(name = "direction", length = 16) private String direction;
    @Column(name = "old_amount") private BigDecimal oldAmount;
    @Column(name = "new_amount") private BigDecimal newAmount;
    @Column(name = "delta_amount") private BigDecimal deltaAmount;
    @Column(name = "pension") private Boolean pension;
    @Column(name = "old_snapshot") private String oldSnapshot;
    @Column(name = "new_snapshot", nullable = false) private String newSnapshot;
    @Column(name = "new_calculation_fingerprint", nullable = false, length = 64) private String newCalculationFingerprint;
    @Column(name = "reconciliation_key", nullable = false, length = 64) private String reconciliationKey;
    @Column(name = "pay_month") private LocalDate payMonth;
    @Column(name = "settlement_month") private LocalDate settlementMonth;
    @Column(name = "open_payroll_attested") private Boolean openPayrollAttested;
    @Column(name = "open_payroll_attested_at") private LocalDateTime openPayrollAttestedAt;
    @Column(name = "open_payroll_attested_by", length = 64) private String openPayrollAttestedBy;
    @Column(name = "adjustment_source_reference", length = 100) private String adjustmentSourceReference;
    @Column(name = "salary_lump_sum_uuid", length = 36) private String salaryLumpSumUuid;
    @Column(name = "external_settlement_ref", length = 255) private String externalSettlementRef;
    @Column(name = "settled_delta_amount") private BigDecimal settledDeltaAmount;
    @Column(name = "settlement_note", length = 1000) private String settlementNote;
    @Version @Column(name = "version", nullable = false) private Long version = 0L;
    @Column(name = "detected_at", nullable = false) private LocalDateTime detectedAt;
    @Column(name = "detected_by", nullable = false, length = 64) private String detectedBy;
    @Column(name = "previewed_at") private LocalDateTime previewedAt;
    @Column(name = "previewed_by", length = 64) private String previewedBy;
    @Column(name = "confirmed_at") private LocalDateTime confirmedAt;
    @Column(name = "confirmed_by", length = 64) private String confirmedBy;
    @Column(name = "settled_at") private LocalDateTime settledAt;
    @Column(name = "settled_by", length = 64) private String settledBy;
    @Column(name = "last_attempt_at", nullable = false) private LocalDateTime lastAttemptAt;
    @Column(name = "attempt_count", nullable = false) private Integer attemptCount = 1;
    @Column(name = "created_at", nullable = false) private LocalDateTime createdAt;
    @Column(name = "updated_at", nullable = false) private LocalDateTime updatedAt;
}
