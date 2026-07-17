package dk.trustworks.intranet.aggregates.invoice.bonus.services;

import dk.trustworks.intranet.aggregates.invoice.bonus.dto.TeamleadAdjustmentRequest;
import dk.trustworks.intranet.aggregates.invoice.bonus.dto.TeamleadBonusAdjustmentDTO;
import dk.trustworks.intranet.aggregates.invoice.bonus.dto.TeamleadBonusSalaryExclusionDTO;
import dk.trustworks.intranet.aggregates.invoice.bonus.dto.TeamleadSalaryExclusionRequest;
import dk.trustworks.intranet.aggregates.invoice.bonus.model.SalaryExclusionMode;
import dk.trustworks.intranet.aggregates.invoice.bonus.model.TeamleadAdjustmentType;
import dk.trustworks.intranet.aggregates.invoice.bonus.model.TeamleadBonusAdjustment;
import dk.trustworks.intranet.aggregates.invoice.bonus.model.TeamleadBonusSalaryExclusion;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.transaction.Transactional;
import jakarta.ws.rs.BadRequestException;
import jakarta.ws.rs.ClientErrorException;
import jakarta.ws.rs.NotFoundException;
import jakarta.ws.rs.core.Response;
import lombok.extern.jbosslog.JBossLog;
import org.hibernate.exception.ConstraintViolationException;

import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;

/**
 * CRUD for the teamlead-bonus admin adjustments and salary-exclusion overrides. Enforces the
 * business rules from spec §3 (single UTIL_OVERRIDE per leader/FY, value/amount bounds, one
 * salary-exclusion per user/FY) and returns 409 on conflicts.
 */
@JBossLog
@ApplicationScoped
public class TeamleadBonusAdminService {

    private static final double MAX_UTIL_OVERRIDE = 1.5;

    // =====================================================================
    // Adjustments
    // =====================================================================

    public List<TeamleadBonusAdjustmentDTO> listAdjustments(int fiscalYear) {
        return TeamleadBonusAdjustment.listByFiscalYear(fiscalYear).stream()
                .map(TeamleadBonusAdjustmentDTO::fromEntity)
                .toList();
    }

    @Transactional
    public TeamleadBonusAdjustmentDTO createAdjustment(TeamleadAdjustmentRequest req, String createdBy) {
        TeamleadAdjustmentType type = parseAdjustmentType(req.adjustmentType());
        validateAdjustmentPayload(type, req.amount(), req.utilOverride());

        if (type == TeamleadAdjustmentType.UTIL_OVERRIDE) {
            rejectSecondUtilOverride(req.fiscalYear(), req.useruuid(), null);
        }

        TeamleadBonusAdjustment a = new TeamleadBonusAdjustment();
        a.fiscalYear = req.fiscalYear();
        a.useruuid = req.useruuid();
        a.adjustmentType = type;
        a.amount = req.amount();
        a.utilOverride = req.utilOverride();
        a.note = req.note();
        a.createdBy = createdBy;
        a.updatedBy = createdBy;
        a.persist();
        flushRejectingDuplicateUtilOverride();
        return TeamleadBonusAdjustmentDTO.fromEntity(a);
    }

    @Transactional
    public TeamleadBonusAdjustmentDTO updateAdjustment(String uuid, TeamleadAdjustmentRequest req, String updatedBy) {
        TeamleadBonusAdjustment a = TeamleadBonusAdjustment.findById(uuid);
        if (a == null) throw new NotFoundException("Adjustment not found: " + uuid);

        TeamleadAdjustmentType type = parseAdjustmentType(req.adjustmentType());
        validateAdjustmentPayload(type, req.amount(), req.utilOverride());

        if (type == TeamleadAdjustmentType.UTIL_OVERRIDE) {
            rejectSecondUtilOverride(req.fiscalYear(), req.useruuid(), uuid);
        }

        a.fiscalYear = req.fiscalYear();
        a.useruuid = req.useruuid();
        a.adjustmentType = type;
        a.amount = req.amount();
        a.utilOverride = req.utilOverride();
        a.note = req.note();
        a.updatedBy = updatedBy;
        a.persist();
        flushRejectingDuplicateUtilOverride();
        return TeamleadBonusAdjustmentDTO.fromEntity(a);
    }

    @Transactional
    public void deleteAdjustment(String uuid) {
        boolean deleted = TeamleadBonusAdjustment.deleteById(uuid);
        if (!deleted) throw new NotFoundException("Adjustment not found: " + uuid);
    }

    // =====================================================================
    // Salary exclusions
    // =====================================================================

    public List<TeamleadBonusSalaryExclusionDTO> listExclusions(int fiscalYear) {
        return TeamleadBonusSalaryExclusion.listByFiscalYear(fiscalYear).stream()
                .map(TeamleadBonusSalaryExclusionDTO::fromEntity)
                .toList();
    }

    @Transactional
    public TeamleadBonusSalaryExclusionDTO createExclusion(TeamleadSalaryExclusionRequest req, String createdBy) {
        SalaryExclusionMode mode = parseMode(req.mode());
        if (TeamleadBonusSalaryExclusion.findByFiscalYearAndUser(req.fiscalYear(), req.useruuid()) != null) {
            throw new ClientErrorException(
                    "A salary exclusion already exists for this user and fiscal year", Response.Status.CONFLICT);
        }

        TeamleadBonusSalaryExclusion e = new TeamleadBonusSalaryExclusion();
        e.fiscalYear = req.fiscalYear();
        e.useruuid = req.useruuid();
        e.mode = mode;
        e.note = req.note();
        e.createdBy = createdBy;
        e.updatedBy = createdBy;
        e.persist();
        return TeamleadBonusSalaryExclusionDTO.fromEntity(e);
    }

    @Transactional
    public void deleteExclusion(String uuid) {
        boolean deleted = TeamleadBonusSalaryExclusion.deleteById(uuid);
        if (!deleted) throw new NotFoundException("Salary exclusion not found: " + uuid);
    }

    // =====================================================================
    // Validation helpers
    // =====================================================================

    private TeamleadAdjustmentType parseAdjustmentType(String raw) {
        try {
            return TeamleadAdjustmentType.valueOf(raw);
        } catch (IllegalArgumentException | NullPointerException e) {
            throw new BadRequestException("Invalid adjustmentType: " + raw);
        }
    }

    private SalaryExclusionMode parseMode(String raw) {
        try {
            return SalaryExclusionMode.valueOf(raw);
        } catch (IllegalArgumentException | NullPointerException e) {
            throw new BadRequestException("Invalid mode: " + raw);
        }
    }

    private void validateAdjustmentPayload(TeamleadAdjustmentType type, Double amount, Double utilOverride) {
        if (type == TeamleadAdjustmentType.UTIL_OVERRIDE) {
            if (utilOverride == null) {
                throw new BadRequestException("utilOverride is required for UTIL_OVERRIDE");
            }
            if (utilOverride < 0.0 || utilOverride > MAX_UTIL_OVERRIDE) {
                throw new BadRequestException("utilOverride must be between 0 and " + MAX_UTIL_OVERRIDE);
            }
        } else {
            if (amount == null) {
                throw new BadRequestException("amount is required for " + type);
            }
            if (amount < 0.0) {
                throw new BadRequestException("amount must be >= 0");
            }
        }
    }

    private void rejectSecondUtilOverride(int fiscalYear, String useruuid, String excludeUuid) {
        boolean exists = TeamleadBonusAdjustment.listByFiscalYearAndUser(fiscalYear, useruuid).stream()
                .filter(a -> a.adjustmentType == TeamleadAdjustmentType.UTIL_OVERRIDE)
                .anyMatch(a -> excludeUuid == null || !excludeUuid.equals(a.uuid));
        if (exists) {
            throw new ClientErrorException(
                    "A utilization override already exists for this leader and fiscal year",
                    Response.Status.CONFLICT);
        }
    }

    /**
     * Flushes the pending insert/update inside this transaction so the generated-key unique
     * constraint ({@code uq_teamlead_bonus_adjustment_util_override}, V414) fires here rather than
     * at commit. {@link #rejectSecondUtilOverride} is only a friendly non-locking pre-check; the
     * constraint is the hard backstop against concurrent duplicates, and this remaps its violation
     * to the contract's 409 instead of a generic 500.
     */
    private void flushRejectingDuplicateUtilOverride() {
        try {
            TeamleadBonusAdjustment.getEntityManager().flush();
        } catch (ConstraintViolationException e) {
            throw new ClientErrorException(
                    "A utilization override already exists for this leader and fiscal year",
                    Response.Status.CONFLICT);
        }
    }
}
