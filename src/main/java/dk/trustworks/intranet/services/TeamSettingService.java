package dk.trustworks.intranet.services;

import dk.trustworks.intranet.domain.user.entity.Team;
import dk.trustworks.intranet.model.TeamSetting;
import dk.trustworks.intranet.userservice.services.TeamService;
import lombok.extern.jbosslog.JBossLog;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.transaction.Transactional;
import jakarta.ws.rs.BadRequestException;
import jakarta.ws.rs.NotFoundException;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import java.util.Set;

/**
 * Application service over {@code team_settings} (V418). Re-homes the IT budget
 * from practice level to team level (decision 5) and resolves a user's IT budget
 * from the team they currently hold a MEMBER role in.
 * <p>
 * The MAX-across-teams resolution is factored into the static package-visible
 * {@link #resolveBudget} helper so it can be unit-tested without a database.
 */
@JBossLog
@ApplicationScoped
public class TeamSettingService {

    /** Application-level fallback for users with no current team (one named constant). */
    public static final int DEFAULT_IT_BUDGET = 25000;

    /** The only team setting key today. */
    static final String IT_BUDGET_KEY = "it_budget";

    static final Set<String> VALID_KEYS = Set.of(IT_BUDGET_KEY);

    @Inject
    TeamService teamService;

    // ── Reads ─────────────────────────────────────────────────────────────

    public List<TeamSetting> findByKey(String key) {
        // Same allow-list as the write path: a future sensitive key must not become
        // readable by every teams:read holder just by existing.
        if (!VALID_KEYS.contains(key)) throw new BadRequestException("Invalid setting key: " + key);
        return TeamSetting.list("settingKey = ?1", key);
    }

    public Optional<TeamSetting> findByTeamAndKey(String teamuuid, String key) {
        return TeamSetting.find("teamuuid = ?1 and settingKey = ?2", teamuuid, key).firstResultOptional();
    }

    public int getItBudgetForTeam(String teamuuid) {
        return findByTeamAndKey(teamuuid, IT_BUDGET_KEY)
                .map(setting -> parseBudget(setting.getSettingValue(), teamuuid))
                .orElse(DEFAULT_IT_BUDGET);
    }

    /**
     * A user's IT budget = the {@code it_budget} of the team they currently hold
     * a MEMBER role in. No team -> default; exactly one -> that team's value;
     * more than one -> MAX (and a warning, since one MEMBER role per user is the
     * intended invariant).
     */
    public int resolveItBudgetForUser(String useruuid) {
        List<Team> teams = teamService.findByRoles(useruuid, LocalDate.now(), "MEMBER");
        List<Integer> budgets = teams.stream()
                .map(team -> getItBudgetForTeam(team.getUuid()))
                .toList();
        if (budgets.size() > 1) {
            log.warnf("resolveItBudgetForUser: user %s is a current MEMBER of %d teams; taking MAX it_budget",
                    useruuid, budgets.size());
        }
        return resolveBudget(budgets, DEFAULT_IT_BUDGET);
    }

    // ── Mutations ─────────────────────────────────────────────────────────

    @Transactional
    public TeamSetting saveSetting(String teamuuid, String key, String value, String updatedBy) {
        if (Team.findById(teamuuid) == null) throw new NotFoundException("Team not found: " + teamuuid);
        if (!VALID_KEYS.contains(key)) throw new BadRequestException("Invalid setting key: " + key);

        int parsed;
        try {
            parsed = Integer.parseInt(value == null ? "" : value.trim());
        } catch (NumberFormatException e) {
            throw new BadRequestException("Setting value must be an integer");
        }
        if (parsed < 0) throw new BadRequestException("Setting value must be non-negative");

        Optional<TeamSetting> existing = findByTeamAndKey(teamuuid, key);
        if (existing.isPresent()) {
            TeamSetting setting = existing.get();
            setting.setSettingValue(String.valueOf(parsed));
            setting.setUpdatedBy(updatedBy);
            return setting;
        }
        TeamSetting setting = new TeamSetting(teamuuid, key, String.valueOf(parsed), updatedBy);
        TeamSetting.persist(setting);
        return setting;
    }

    // ── Pure helpers ──────────────────────────────────────────────────────

    private int parseBudget(String value, String teamuuid) {
        try {
            return Integer.parseInt(value == null ? "" : value.trim());
        } catch (NumberFormatException e) {
            log.warnf("Invalid it_budget value '%s' for team '%s', using default %d", value, teamuuid, DEFAULT_IT_BUDGET);
            return DEFAULT_IT_BUDGET;
        }
    }

    /**
     * Resolves the effective IT budget from the current MEMBER teams' budgets:
     * empty -> {@code defaultBudget}; otherwise the maximum.
     */
    static int resolveBudget(List<Integer> budgets, int defaultBudget) {
        if (budgets == null || budgets.isEmpty()) return defaultBudget;
        return budgets.stream().mapToInt(Integer::intValue).max().orElse(defaultBudget);
    }
}
