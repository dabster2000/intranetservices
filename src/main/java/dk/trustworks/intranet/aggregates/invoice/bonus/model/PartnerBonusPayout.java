package dk.trustworks.intranet.aggregates.invoice.bonus.model;

import io.quarkus.hibernate.orm.panache.PanacheEntityBase;
import jakarta.persistence.*;
import lombok.Getter; import lombok.Setter;
import org.eclipse.microprofile.openapi.annotations.media.Schema;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.UUID;

/**
 * One payout event per (partner group, fiscal year).
 *
 * <p>Created the first time any partner in the group is paid for the fiscal year. It freezes the
 * group sales basis and the per-partner sales bonus so that the remaining partners draw the same
 * split without re-consuming invoices. The APPROVED {@link InvoiceBonus} rows that fed the basis
 * are stamped with this event's {@code uuid} via {@code InvoiceBonus.payoutUuid}, which makes the
 * "an invoice can only ever fund a bonus once" guarantee enforceable: the payout recompute sums
 * only un-stamped rows.</p>
 */
@Getter @Setter
@Entity
@Table(name = "partner_bonus_payouts",
        uniqueConstraints = @UniqueConstraint(name = "uq_partner_bonus_payouts_group_fy",
                columnNames = {"partner_group_uuid", "fiscal_year"}))
@Schema(name = "PartnerBonusPayout",
        description = "Frozen partner-bonus payout event for one (partner group, fiscal year). " +
                "Records the group sales basis and per-partner sales bonus consumed by the payout.")
public class PartnerBonusPayout extends PanacheEntityBase {

    @Id
    @Schema(description = "Payout event UUID", readOnly = true)
    public String uuid;

    @Column(name = "partner_group_uuid", nullable = false, length = 36)
    @Schema(description = "BonusEligibilityGroup UUID this payout belongs to")
    public String partnerGroupUuid;

    @Column(name = "fiscal_year", nullable = false)
    @Schema(description = "Fiscal year starting year (FY YYYY = YYYY-07-01 .. (YYYY+1)-06-30)")
    public int fiscalYear;

    @Column(name = "payout_month")
    @Schema(description = "Month the first partner of the group was paid in (first-of-month)")
    public LocalDate payoutMonth;

    @Column(name = "computed_sales_basis", nullable = false)
    @Schema(description = "Frozen group approved-sales basis consumed by this payout")
    public double computedSalesBasis;

    @Column(name = "sales_bonus_per_partner", nullable = false)
    @Schema(description = "Frozen per-partner sales bonus derived from the basis")
    public double salesBonusPerPartner;

    @Column(name = "partner_count", nullable = false)
    @Schema(description = "Number of partners the basis was split across when frozen")
    public int partnerCount;

    @Column(name = "is_backfill", nullable = false)
    @Schema(description = "True if this event was created by the one-time historical backfill")
    public boolean backfill;

    @Column(name = "created_at", nullable = false)
    @Schema(description = "Creation timestamp (server time)", readOnly = true)
    public LocalDateTime createdAt;

    @Column(name = "created_by", length = 36)
    @Schema(description = "UUID of the user (or 'BACKFILL') who created the event", readOnly = true)
    public String createdBy;

    @PrePersist
    public void prePersist() {
        if (uuid == null) uuid = UUID.randomUUID().toString();
        if (createdAt == null) createdAt = LocalDateTime.now();
    }

    public static PartnerBonusPayout findByGroupAndYear(String partnerGroupUuid, int fiscalYear) {
        return find("partnerGroupUuid = ?1 and fiscalYear = ?2", partnerGroupUuid, fiscalYear).firstResult();
    }
}
