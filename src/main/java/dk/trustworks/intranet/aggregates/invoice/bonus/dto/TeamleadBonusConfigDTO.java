package dk.trustworks.intranet.aggregates.invoice.bonus.dto;

import dk.trustworks.intranet.aggregates.invoice.bonus.model.TeamleadBonusConfig;
import org.eclipse.microprofile.openapi.annotations.media.Schema;

/**
 * Effective teamlead bonus configuration for a fiscal year. This is a plain, DB-free value carrier
 * so the pure math ({@code TeamleadBonusMath}) and unit tests can use it without CDI.
 *
 * <p>{@code effectiveSourceFiscalYear} tells which FY row supplied the values: the requested FY when
 * an exact row exists (or code defaults were applied), or an earlier FY when values were inherited.</p>
 */
@Schema(name = "TeamleadBonusConfig", description = "Effective teamlead bonus configuration for a fiscal year")
public record TeamleadBonusConfigDTO(
        int fiscalYear,
        double poolSharePercent,
        double minUtilThreshold,
        double productionThresholdAnnual,
        double productionCommissionPercent,
        double teamFactorTier1,
        double teamFactorTier2,
        double teamFactorTier3,
        double teamFactorTier2From,
        double teamFactorTier3From,
        Double overskudOverride,
        String overskudOverrideNote,
        Integer effectiveSourceFiscalYear
) {

    /** Code defaults matching the spec numbers, used when no config row is available. */
    public static TeamleadBonusConfigDTO codeDefaults(int fiscalYear) {
        return new TeamleadBonusConfigDTO(
                fiscalYear,
                0.05,          // poolSharePercent
                0.65,          // minUtilThreshold
                1_100_000.0,   // productionThresholdAnnual
                0.20,          // productionCommissionPercent
                1.0,           // teamFactorTier1
                1.5,           // teamFactorTier2
                2.0,           // teamFactorTier3
                7.0,           // teamFactorTier2From
                11.0,          // teamFactorTier3From
                null,          // overskudOverride
                null,          // overskudOverrideNote
                fiscalYear     // effectiveSourceFiscalYear = requested FY (defaults)
        );
    }

    /** Map a persisted row to the effective DTO for the requested fiscal year. */
    public static TeamleadBonusConfigDTO fromEntity(TeamleadBonusConfig e, int requestedFiscalYear) {
        return new TeamleadBonusConfigDTO(
                requestedFiscalYear,
                e.poolSharePercent,
                e.minUtilThreshold,
                e.productionThresholdAnnual,
                e.productionCommissionPercent,
                e.teamFactorTier1,
                e.teamFactorTier2,
                e.teamFactorTier3,
                e.teamFactorTier2From,
                e.teamFactorTier3From,
                e.overskudOverride,
                e.overskudOverrideNote,
                e.fiscalYear
        );
    }
}
