package dk.trustworks.intranet.aggregates.invoice.bonus.services;

import dk.trustworks.intranet.aggregates.invoice.bonus.dto.TeamleadBonusConfigDTO;
import dk.trustworks.intranet.aggregates.invoice.bonus.model.TeamleadBonusConfig;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.transaction.Transactional;
import jakarta.ws.rs.BadRequestException;
import lombok.extern.jbosslog.JBossLog;

import java.time.LocalDateTime;

/**
 * Resolves and persists the FY-versioned teamlead bonus configuration.
 *
 * <p>Effective-config fallback chain (spec §0.1 / §3):
 * <ol>
 *   <li>exact {@code teamlead_bonus_config} row for the requested fiscal year, else</li>
 *   <li>the latest row with {@code fiscal_year < requestedFY}, else</li>
 *   <li>code defaults ({@link TeamleadBonusConfigDTO#codeDefaults(int)}).</li>
 * </ol>
 * The returned DTO always reports {@code effectiveSourceFiscalYear} — the requested FY when an exact
 * row (or code defaults) supplied the values, or the earlier FY the values were inherited from.</p>
 */
@JBossLog
@ApplicationScoped
public class TeamleadBonusConfigService {

    /** Effective configuration for a fiscal year, following the fallback chain. */
    public TeamleadBonusConfigDTO getEffectiveConfig(int fiscalYear) {
        TeamleadBonusConfig exact = TeamleadBonusConfig.findByFiscalYear(fiscalYear);
        if (exact != null) {
            return TeamleadBonusConfigDTO.fromEntity(exact, fiscalYear);
        }
        TeamleadBonusConfig fallback = TeamleadBonusConfig.findLatestBefore(fiscalYear);
        if (fallback != null) {
            return TeamleadBonusConfigDTO.fromEntity(fallback, fiscalYear);
        }
        return TeamleadBonusConfigDTO.codeDefaults(fiscalYear);
    }

    /**
     * Upsert the configuration row for {@code request.fiscalYear()}. Validates ranges before
     * persisting. Returns the effective config for that FY (now backed by the persisted row).
     */
    @Transactional
    public TeamleadBonusConfigDTO upsertConfig(TeamleadBonusConfigDTO request, String updatedBy) {
        validate(request);
        int fy = request.fiscalYear();

        TeamleadBonusConfig row = TeamleadBonusConfig.findByFiscalYear(fy);
        if (row == null) {
            row = new TeamleadBonusConfig();
            row.fiscalYear = fy;
        }
        row.poolSharePercent = request.poolSharePercent();
        row.minUtilThreshold = request.minUtilThreshold();
        row.productionThresholdAnnual = request.productionThresholdAnnual();
        row.productionCommissionPercent = request.productionCommissionPercent();
        row.teamFactorTier1 = request.teamFactorTier1();
        row.teamFactorTier2 = request.teamFactorTier2();
        row.teamFactorTier3 = request.teamFactorTier3();
        row.teamFactorTier2From = request.teamFactorTier2From();
        row.teamFactorTier3From = request.teamFactorTier3From();
        row.overskudOverride = request.overskudOverride();
        row.overskudOverrideNote = request.overskudOverrideNote();
        row.updatedAt = LocalDateTime.now();
        row.updatedBy = updatedBy;
        row.persist();

        log.infof("Teamlead bonus config upserted for FY %d by %s", fy, updatedBy);
        return TeamleadBonusConfigDTO.fromEntity(row, fy);
    }

    private void validate(TeamleadBonusConfigDTO c) {
        if (c == null || c.fiscalYear() < 2000 || c.fiscalYear() > 2999) {
            throw new BadRequestException("fiscalYear is required and must be 2000-2999");
        }
        requirePercent("poolSharePercent", c.poolSharePercent());
        requirePercent("minUtilThreshold", c.minUtilThreshold());
        requirePercent("productionCommissionPercent", c.productionCommissionPercent());
        requireFactor("teamFactorTier1", c.teamFactorTier1());
        requireFactor("teamFactorTier2", c.teamFactorTier2());
        requireFactor("teamFactorTier3", c.teamFactorTier3());
        if (c.productionThresholdAnnual() < 0) {
            throw new BadRequestException("productionThresholdAnnual must be >= 0");
        }
        if (c.teamFactorTier2From() >= c.teamFactorTier3From()) {
            throw new BadRequestException("teamFactorTier2From must be < teamFactorTier3From");
        }
        // Column is VARCHAR(500) — reject oversized notes as 400 instead of failing at the DB layer.
        if (c.overskudOverrideNote() != null && c.overskudOverrideNote().length() > 500) {
            throw new BadRequestException("overskudOverrideNote must be at most 500 characters");
        }
    }

    private static void requirePercent(String field, double value) {
        if (value < 0.0 || value > 1.0) {
            throw new BadRequestException(field + " must be between 0 and 1");
        }
    }

    private static void requireFactor(String field, double value) {
        if (value < 0.0 || value > 5.0) {
            throw new BadRequestException(field + " must be between 0 and 5");
        }
    }
}
