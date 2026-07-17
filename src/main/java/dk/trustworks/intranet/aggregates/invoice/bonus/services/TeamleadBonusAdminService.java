package dk.trustworks.intranet.aggregates.invoice.bonus.services;

import dk.trustworks.intranet.aggregates.invoice.bonus.dto.TeamleadAdjustmentRequest;
import dk.trustworks.intranet.aggregates.invoice.bonus.dto.TeamleadBonusAdjustmentDTO;
import dk.trustworks.intranet.aggregates.invoice.bonus.dto.TeamleadBonusSalaryExclusionDTO;
import dk.trustworks.intranet.aggregates.invoice.bonus.dto.TeamleadLeaderExclusionDTO;
import dk.trustworks.intranet.aggregates.invoice.bonus.dto.TeamleadLeaderExclusionRequest;
import dk.trustworks.intranet.aggregates.invoice.bonus.dto.TeamleadMemberOverrideDTO;
import dk.trustworks.intranet.aggregates.invoice.bonus.dto.TeamleadMemberOverrideRequest;
import dk.trustworks.intranet.aggregates.invoice.bonus.dto.TeamleadSalaryExclusionRequest;
import dk.trustworks.intranet.aggregates.invoice.bonus.model.SalaryExclusionMode;
import dk.trustworks.intranet.aggregates.invoice.bonus.model.TeamleadAdjustmentType;
import dk.trustworks.intranet.aggregates.invoice.bonus.model.TeamleadBonusAdjustment;
import dk.trustworks.intranet.aggregates.invoice.bonus.model.TeamleadBonusLeaderExclusion;
import dk.trustworks.intranet.aggregates.invoice.bonus.model.TeamleadBonusMemberOverride;
import dk.trustworks.intranet.aggregates.invoice.bonus.model.TeamleadBonusSalaryExclusion;
import dk.trustworks.intranet.domain.user.entity.Team;
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
    private static final int MIN_FISCAL_YEAR = 2000;
    private static final int MAX_FISCAL_YEAR = 2999;
    private static final int MAX_NOTE_LENGTH = 500;

    // =====================================================================
    // Member overrides (editable calculation sources — spec §1/§2)
    // =====================================================================

    /**
     * Upserts a per-member inclusion override for a (team, user, month). The team must exist and be a
     * teamleadbonus team; the month must be a completed month in a sane range.
     */
    @Transactional
    public TeamleadMemberOverrideDTO upsertMemberOverride(String teamId, TeamleadMemberOverrideRequest req, String by) {
        requireTeamleadBonusTeam(teamId);
        String month = req.month();
        validateMonthKey(month);
        validateNote(req.note());

        TeamleadBonusMemberOverride entity =
                TeamleadBonusMemberOverride.findByTeamUserMonth(teamId, req.useruuid(), month);
        if (entity == null) {
            entity = new TeamleadBonusMemberOverride();
            entity.teamuuid = teamId;
            entity.useruuid = req.useruuid();
            entity.month = month;
            entity.createdBy = by;
        }
        entity.included = Boolean.TRUE.equals(req.included());
        entity.note = req.note();
        entity.updatedBy = by;
        entity.persist();
        flushRemappingDuplicateMemberOverride();
        return TeamleadMemberOverrideDTO.fromEntity(entity);
    }

    /** Deletes a member override; idempotent (no error when the row is already absent). */
    @Transactional
    public void deleteMemberOverride(String teamId, String useruuid, String month) {
        TeamleadBonusMemberOverride entity =
                TeamleadBonusMemberOverride.findByTeamUserMonth(teamId, useruuid, month);
        if (entity != null) {
            entity.delete();
        }
    }

    // =====================================================================
    // Leader exclusions (editable calculation sources — spec §6)
    // =====================================================================

    public List<TeamleadLeaderExclusionDTO> listLeaderExclusions(int fiscalYear) {
        return TeamleadBonusLeaderExclusion.listByFiscalYear(fiscalYear).stream()
                .map(TeamleadLeaderExclusionDTO::fromEntity)
                .toList();
    }

    /**
     * Idempotent create of a leader exclusion for (fiscal year, team, user). Returns the existing row
     * (flagged {@code created = false}) when one already exists, else creates it ({@code created = true}).
     */
    @Transactional
    public LeaderExclusionResult createLeaderExclusion(TeamleadLeaderExclusionRequest req, String by) {
        requireTeamleadBonusTeam(req.teamId());
        validateFiscalYear(req.fiscalYear());
        validateNote(req.note());

        TeamleadBonusLeaderExclusion existing = TeamleadBonusLeaderExclusion.findByFiscalYearTeamUser(
                req.fiscalYear(), req.teamId(), req.useruuid());
        if (existing != null) {
            return new LeaderExclusionResult(TeamleadLeaderExclusionDTO.fromEntity(existing), false);
        }

        TeamleadBonusLeaderExclusion entity = new TeamleadBonusLeaderExclusion();
        entity.fiscalYear = req.fiscalYear();
        entity.teamuuid = req.teamId();
        entity.useruuid = req.useruuid();
        entity.note = req.note();
        entity.createdBy = by;
        entity.persist();
        flushRemappingDuplicateExclusion();
        return new LeaderExclusionResult(TeamleadLeaderExclusionDTO.fromEntity(entity), true);
    }

    /** Deletes a leader exclusion; idempotent (no error when the row is already absent). */
    @Transactional
    public void deleteLeaderExclusion(int fiscalYear, String teamId, String useruuid) {
        TeamleadBonusLeaderExclusion entity =
                TeamleadBonusLeaderExclusion.findByFiscalYearTeamUser(fiscalYear, teamId, useruuid);
        if (entity != null) {
            entity.delete();
        }
    }

    /** Create outcome for a leader exclusion: the row plus whether it was newly created. */
    public record LeaderExclusionResult(TeamleadLeaderExclusionDTO dto, boolean created) {}

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

    /**
     * Flushes the pending insert so the {@code uq_teamlead_bonus_leader_exclusion_fy_team_user}
     * constraint (V415) fires here. {@link #createLeaderExclusion} already pre-checks for an existing
     * row (returning it idempotently); this closes the concurrent-double-create race, treating the
     * loser as the same idempotent outcome (return the row) rather than a 500.
     */
    private void flushRemappingDuplicateExclusion() {
        try {
            TeamleadBonusLeaderExclusion.getEntityManager().flush();
        } catch (ConstraintViolationException e) {
            throw new ClientErrorException(
                    "A leader exclusion already exists for this fiscal year, team and user",
                    Response.Status.CONFLICT);
        }
    }

    /**
     * Flushes the pending insert/update so the {@code uq_teamlead_bonus_member_override_team_user_month}
     * constraint (V415) fires here. {@link #upsertMemberOverride}'s find-then-insert is non-locking, so
     * a concurrent double-PUT loser would otherwise surface the constraint violation at commit as a
     * generic 500; remap it to a 409 the client can retry.
     */
    private void flushRemappingDuplicateMemberOverride() {
        try {
            TeamleadBonusMemberOverride.getEntityManager().flush();
        } catch (ConstraintViolationException e) {
            throw new ClientErrorException(
                    "A member override already exists for this team, user and month — retry",
                    Response.Status.CONFLICT);
        }
    }

    private void requireTeamleadBonusTeam(String teamId) {
        Team team = Team.findById(teamId);
        if (team == null) {
            throw new NotFoundException("Team not found: " + teamId);
        }
        if (!team.isTeamleadbonus()) {
            throw new BadRequestException("Team is not a teamlead-bonus team: " + teamId);
        }
    }

    private void validateMonthKey(String month) {
        if (month == null || !month.matches("^\\d{6}$")) {
            throw new BadRequestException("month must be in YYYYMM format");
        }
        int year = Integer.parseInt(month.substring(0, 4));
        int monthOfYear = Integer.parseInt(month.substring(4, 6));
        if (year < MIN_FISCAL_YEAR || year > MAX_FISCAL_YEAR || monthOfYear < 1 || monthOfYear > 12) {
            throw new BadRequestException("month is out of range: " + month);
        }
    }

    private void validateFiscalYear(Integer fiscalYear) {
        if (fiscalYear == null || fiscalYear < MIN_FISCAL_YEAR || fiscalYear > MAX_FISCAL_YEAR) {
            throw new BadRequestException("fiscalYear must be " + MIN_FISCAL_YEAR + "-" + MAX_FISCAL_YEAR);
        }
    }

    private void validateNote(String note) {
        if (note != null && note.length() > MAX_NOTE_LENGTH) {
            throw new BadRequestException("note must be at most " + MAX_NOTE_LENGTH + " characters");
        }
    }
}
