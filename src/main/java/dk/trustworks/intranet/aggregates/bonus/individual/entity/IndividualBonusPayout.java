package dk.trustworks.intranet.aggregates.bonus.individual.entity;

import dk.trustworks.intranet.aggregates.bonus.individual.model.MaterializedPayoutCommand;
import io.quarkus.hibernate.orm.panache.PanacheEntityBase;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import lombok.Data;
import lombok.EqualsAndHashCode;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.UUID;

/**
 * An immutable audit snapshot written at materialisation time, alongside each individual-bonus lump sum,
 * so a past payout stays fully reproducible even after the driving {@code individual_bonus_rule} is later
 * edited or soft-deleted (bonuses are money — a past payout must be reconstructable).
 * <p>
 * It freezes the effective spec ({@code specJson} — the reproducibility anchor) plus the resolved inputs
 * that produced the amount ({@code basisAmount}, {@code monthsEmployed}). Keyed on the same
 * {@code sourceReference} as the lump sum (UNIQUE index → idempotent). {@code specJson} is a plain String
 * mapped to a LONGTEXT column — NOT a native JSON column, which crashes Quarkus boot here (mirrors
 * {@code IndividualBonusRule.spec}).
 */
@Data
@EqualsAndHashCode(callSuper = false, onlyExplicitlyIncluded = true)
@Entity
@Table(name = "individual_bonus_payout")
public class IndividualBonusPayout extends PanacheEntityBase {

    @Id
    @Size(max = 36)
    @EqualsAndHashCode.Include
    @Column(name = "uuid", nullable = false, length = 36)
    private String uuid;

    @NotNull
    @Size(max = 255)
    @Column(name = "source_reference", nullable = false, length = 255)
    private String sourceReference;

    @NotNull
    @Column(name = "rule_uuid", nullable = false, length = 36)
    private String ruleUuid;

    @NotNull
    @Column(name = "user_uuid", nullable = false, length = 36)
    private String userUuid;

    @NotNull
    @Column(name = "month", nullable = false)
    private LocalDate month;

    @NotNull
    @Size(max = 32)
    @Column(name = "kind", nullable = false, length = 32)
    private String kind;

    @NotNull
    @Column(name = "amount", nullable = false)
    private BigDecimal amount;

    /** The effective rule spec JSON at payout time — stored as text, NOT a native JSON column. */
    @NotNull
    @Column(name = "spec_json", nullable = false)
    private String specJson;

    @Column(name = "basis_amount")
    private BigDecimal basisAmount;

    @Column(name = "months_employed")
    private Integer monthsEmployed;

    @Column(name = "earning_month")
    private LocalDate earningMonth;

    @Column(name = "pay_month")
    private LocalDate payMonth;

    @Column(name = "company_uuid", length = 36)
    private String companyUuid;

    @Column(name = "materialization_status", length = 40)
    private String materializationStatus;

    @Column(name = "snapshot_version")
    private Short snapshotVersion;

    /** Canonical Snapshot V2 JSON. Kept as LONGTEXT for the established Hibernate mapper boundary. */
    @Column(name = "calculation_snapshot")
    private String calculationSnapshot;

    @Column(name = "calculation_fingerprint", length = 64)
    private String calculationFingerprint;

    @Column(name = "actor_uuid", length = 64)
    private String actorUuid;

    @Column(name = "salary_lump_sum_uuid", length = 36)
    private String salaryLumpSumUuid;

    @Column(name = "facts_as_of")
    private LocalDateTime factsAsOf;

    @NotNull
    @Column(name = "created_at", nullable = false, updatable = false)
    private LocalDateTime createdAt;

    /** Build a snapshot from a materialisation command. Pure — no persistence side effect. */
    public static IndividualBonusPayout snapshot(MaterializedPayoutCommand cmd) {
        IndividualBonusPayout s = new IndividualBonusPayout();
        s.uuid = UUID.randomUUID().toString();
        s.sourceReference = cmd.sourceReference();
        s.ruleUuid = cmd.ruleUuid();
        s.userUuid = cmd.userUuid();
        s.month = cmd.month();
        s.kind = cmd.kind() != null ? cmd.kind().name() : null;
        s.amount = cmd.amount();
        s.specJson = cmd.specJson();
        s.basisAmount = cmd.basisAmount();
        s.monthsEmployed = cmd.monthsEmployed();
        s.createdAt = LocalDateTime.now();
        return s;
    }
}
