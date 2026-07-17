package dk.trustworks.intranet.aggregates.invoice.bonus.model;

import io.quarkus.hibernate.orm.panache.PanacheEntityBase;
import jakarta.persistence.*;
import lombok.Getter;
import lombok.Setter;
import org.eclipse.microprofile.openapi.annotations.media.Schema;

import java.time.LocalDateTime;

/**
 * FY-versioned configuration for the teamlead bonus program. PK is the fiscal-year start year.
 *
 * <p>The effective configuration for a fiscal year is resolved with a fallback chain
 * (exact FY row → latest row before FY → code defaults) by
 * {@link dk.trustworks.intranet.aggregates.invoice.bonus.services.TeamleadBonusConfigService}.
 * Cross-aggregate references are absent by design — this is a self-contained settings row.</p>
 */
@Getter
@Setter
@Entity
@Table(name = "teamlead_bonus_config")
@Schema(name = "TeamleadBonusConfig",
        description = "FY-versioned teamlead bonus configuration (pool share, thresholds, factor tiers, overskud override).")
public class TeamleadBonusConfig extends PanacheEntityBase {

    @Id
    @Column(name = "fiscal_year", nullable = false)
    @Schema(description = "Fiscal year starting year (FY YYYY = YYYY-07-01 .. (YYYY+1)-06-30)")
    public int fiscalYear;

    @Column(name = "pool_share_percent", nullable = false)
    public double poolSharePercent = 0.05;

    @Column(name = "min_util_threshold", nullable = false)
    public double minUtilThreshold = 0.65;

    @Column(name = "production_threshold_annual", nullable = false)
    public double productionThresholdAnnual = 1_100_000.0;

    @Column(name = "production_commission_percent", nullable = false)
    public double productionCommissionPercent = 0.20;

    @Column(name = "team_factor_tier1", nullable = false)
    public double teamFactorTier1 = 1.0;

    @Column(name = "team_factor_tier2", nullable = false)
    public double teamFactorTier2 = 1.5;

    @Column(name = "team_factor_tier3", nullable = false)
    public double teamFactorTier3 = 2.0;

    @Column(name = "team_factor_tier2_from", nullable = false)
    public double teamFactorTier2From = 7.0;

    @Column(name = "team_factor_tier3_from", nullable = false)
    public double teamFactorTier3From = 11.0;

    @Column(name = "overskud_override")
    @Schema(description = "Manual pool-basis override for this FY; NULL = use the computed estimate")
    public Double overskudOverride;

    @Column(name = "overskud_override_note", length = 500)
    public String overskudOverrideNote;

    @Column(name = "updated_at")
    public LocalDateTime updatedAt;

    @Column(name = "updated_by", length = 100)
    public String updatedBy;

    public static TeamleadBonusConfig findByFiscalYear(int fiscalYear) {
        return findById(fiscalYear);
    }

    /**
     * Latest configuration row strictly before the given fiscal year (for the fallback chain).
     */
    public static TeamleadBonusConfig findLatestBefore(int fiscalYear) {
        return find("fiscalYear < ?1 order by fiscalYear desc", fiscalYear).firstResult();
    }
}
