package dk.trustworks.intranet.aggregates.invoice.bonus.model;

import io.quarkus.hibernate.orm.panache.PanacheEntityBase;
import jakarta.persistence.*;
import lombok.Getter;
import lombok.Setter;
import org.eclipse.microprofile.openapi.annotations.media.Schema;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.UUID;

/**
 * One payout event per (fiscal year, leader). The unique (fiscal_year, useruuid) constraint is the
 * hard "fund once" backstop; the per-component {@code salary_lump_sum.source_reference} unique index
 * is the second layer. Records the server-recomputed component amounts, the created lump-sum UUIDs
 * and a JSON snapshot of the full calculation for audit.
 */
@Getter
@Setter
@Entity
@Table(name = "teamlead_bonus_payouts",
        uniqueConstraints = @UniqueConstraint(name = "uq_teamlead_bonus_payouts_fy_user",
                columnNames = {"fiscal_year", "useruuid"}))
@Schema(name = "TeamleadBonusPayout",
        description = "Frozen teamlead-bonus payout event for one (fiscal year, leader).")
public class TeamleadBonusPayout extends PanacheEntityBase {

    @Id
    @Column(name = "uuid", nullable = false, length = 36)
    public String uuid;

    @Column(name = "fiscal_year", nullable = false)
    public int fiscalYear;

    @Column(name = "useruuid", nullable = false, length = 36)
    public String useruuid;

    @Column(name = "payout_month", nullable = false)
    public LocalDate payoutMonth;

    @Column(name = "pool_amount")
    public double poolAmount;

    @Column(name = "production_amount")
    public double productionAmount;

    @Column(name = "split_amount")
    public double splitAmount;

    @Column(name = "prepaid_deduction")
    public double prepaidDeduction;

    @Column(name = "total_amount")
    public double totalAmount;

    @Column(name = "pool_lump_sum_uuid", length = 36)
    public String poolLumpSumUuid;

    @Column(name = "production_lump_sum_uuid", length = 36)
    public String productionLumpSumUuid;

    @Column(name = "split_lump_sum_uuid", length = 36)
    public String splitLumpSumUuid;

    @Column(name = "calculation_snapshot", columnDefinition = "LONGTEXT")
    public String calculationSnapshot;

    @Column(name = "created_at")
    public LocalDateTime createdAt;

    @Column(name = "created_by", length = 100)
    public String createdBy;

    @PrePersist
    public void prePersist() {
        if (uuid == null) uuid = UUID.randomUUID().toString();
        if (createdAt == null) createdAt = LocalDateTime.now();
    }

    public static TeamleadBonusPayout findByFiscalYearAndUser(int fiscalYear, String useruuid) {
        return find("fiscalYear = ?1 and useruuid = ?2", fiscalYear, useruuid).firstResult();
    }
}
