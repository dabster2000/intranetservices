package dk.trustworks.intranet.aggregates.finance.services;

import dk.trustworks.intranet.aggregates.finance.dto.JuniorTalentDTO;
import dk.trustworks.intranet.aggregates.finance.dto.JuniorTalentDTO.JuniorRow;
import dk.trustworks.intranet.aggregates.finance.dto.NameRef;
import dk.trustworks.intranet.aggregates.finance.dto.TeamCapacityDTO;
import dk.trustworks.intranet.aggregates.finance.dto.TeamCapacityDTO.MemberCapacity;
import dk.trustworks.intranet.aggregates.finance.dto.TeamCapacityDTO.WeekCell;
import dk.trustworks.intranet.aggregates.userprofile.dto.CompetenceTagDTO;
import dk.trustworks.intranet.aggregates.userprofile.model.UserCompetenceTag;
import dk.trustworks.intranet.aggregates.userprofile.model.UserProfileExtension;
import dk.trustworks.intranet.contracts.services.ZeroRateWatchlistService;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.persistence.EntityManager;
import jakarta.persistence.Tuple;
import lombok.extern.jbosslog.JBossLog;

import java.time.DayOfWeek;
import java.time.LocalDate;
import java.time.temporal.TemporalAdjusters;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;

/**
 * Junior Talent (JK Team 2.0 WP7, spec §4.7): every active STUDENT with their declared
 * availability over the next weeks, faglighed, tags and graduation, plus the step-up
 * watchlist. Availability = declared − contract budget − approved internal hours, with the
 * junior's own declared week as the denominator (never 37 h); undeclared working days are
 * reported as such (D7), and orlov renders as "on leave", not as a percentage.
 */
@JBossLog
@ApplicationScoped
public class JuniorTalentService {

    public static final int DEFAULT_HORIZON_WEEKS = 4;
    public static final int MAX_HORIZON_WEEKS = 12;
    public static final int STEP_UP_WINDOW_DAYS = 30;
    /** Orlov covers the window when leave hours fall on at least this share of its working days. */
    static final double ON_LEAVE_SHARE = 0.5;

    @Inject
    EntityManager em;

    @Inject
    HourlyTeamDashboardService hourlyTeamDashboardService;

    @Inject
    ZeroRateWatchlistService zeroRateWatchlistService;

    public JuniorTalentDTO build(LocalDate today, int horizonWeeks) {
        int weeks = Math.min(Math.max(horizonWeeks, 1), MAX_HORIZON_WEEKS);
        LocalDate from = today.with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY));
        LocalDate to = from.plusWeeks(weeks).minusDays(1);

        Set<String> juniors = activeStudents(today);
        if (juniors.isEmpty()) {
            return new JuniorTalentDTO(from, to, weeks, List.of(), List.of());
        }

        TeamCapacityDTO capacity = hourlyTeamDashboardService.getCapacity(juniors, from, weeks, today);
        Map<String, UserProfileExtension> profiles = UserProfileExtension.mapForUsers(juniors);
        Map<String, List<CompetenceTagDTO>> tags = new HashMap<>();
        for (UserCompetenceTag t : UserCompetenceTag.findForUsers(juniors)) {
            tags.computeIfAbsent(t.getUseruuid(), k -> new ArrayList<>()).add(CompetenceTagDTO.from(t));
        }
        Map<String, String> teamNames = teamNames(juniors, today);
        Map<String, Set<String>> clients = currentClients(juniors, from, to);
        Set<String> onLeave = onLeave(juniors, from, to);

        List<JuniorRow> rows = new ArrayList<>();
        for (MemberCapacity m : capacity.members()) {
            UserProfileExtension p = profiles.get(m.userId());
            rows.add(row(m, p, tags.getOrDefault(m.userId(), List.of()), teamNames.get(m.userId()),
                    new ArrayList<>(clients.getOrDefault(m.userId(), Set.of())), onLeave.contains(m.userId())));
        }
        rows.sort((a, b) -> HourlyTeamDashboardService.compareNames(
                new NameRef(a.userId(), a.firstname(), a.lastname()), new NameRef(b.userId(), b.firstname(), b.lastname())));

        return new JuniorTalentDTO(from, to, weeks, rows,
                zeroRateWatchlistService.watchlist(today, STEP_UP_WINDOW_DAYS, true));
    }

    /** Package-private for the test: one junior's row from the capacity cells. */
    static JuniorRow row(MemberCapacity m, UserProfileExtension profile, List<CompetenceTagDTO> tags,
                         String teamName, List<String> currentClients, boolean onLeave) {
        double declared = 0;
        boolean anyDeclared = false;
        int undeclared = 0;
        double contractBudget = 0;
        double internalBudget = 0;
        for (WeekCell w : m.weeks()) {
            if (w.declaredHours() != null) {
                declared += w.declaredHours();
                anyDeclared = true;
            }
            undeclared += w.undeclaredDays();
            contractBudget += w.contractBudgetHours();
            internalBudget += w.internalBudgetHours();
        }
        Double declaredHours = anyDeclared ? declared : null;
        double free = anyDeclared ? Math.max(declared - contractBudget - internalBudget, 0.0) : 0.0;
        Double freePercent = anyDeclared && !onLeave && declared > 0 ? free / declared * 100.0 : null;
        return new JuniorRow(
                m.userId(), m.firstname(), m.lastname(), teamName,
                profile == null ? null : profile.getStudyLevel(),
                profile == null ? null : profile.getExpectedGraduation(),
                profile == null ? null : profile.getPrimaryDiscipline(),
                tags,
                declaredHours, undeclared, contractBudget, internalBudget, free, freePercent, onLeave,
                currentClients);
    }

    /** Every user whose current status is ACTIVE with type STUDENT — the declaring population. */
    Set<String> activeStudents(LocalDate today) {
        @SuppressWarnings("unchecked")
        List<String> uuids = em.createNativeQuery("""
                SELECT us.useruuid
                FROM userstatus us
                WHERE us.statusdate = (
                          SELECT MAX(us2.statusdate) FROM userstatus us2
                          WHERE us2.useruuid = us.useruuid AND us2.statusdate <= :today
                      )
                  AND us.status = 'ACTIVE'
                  AND us.type = 'STUDENT'
                """)
                .setParameter("today", today)
                .getResultList();
        return new LinkedHashSet<>(uuids);
    }

    private Map<String, String> teamNames(Set<String> uuids, LocalDate today) {
        Map<String, String> out = new HashMap<>();
        @SuppressWarnings("unchecked")
        List<Tuple> rows = em.createNativeQuery("""
                SELECT tr.useruuid, t.name
                FROM teamroles tr
                JOIN team t ON t.uuid = tr.teamuuid
                WHERE tr.useruuid IN (:uuids)
                  AND tr.membertype = 'MEMBER'
                  AND tr.startdate <= :today
                  AND (tr.enddate IS NULL OR tr.enddate > :today)
                """, Tuple.class)
                .setParameter("uuids", uuids)
                .setParameter("today", today)
                .getResultList();
        for (Tuple r : rows) out.putIfAbsent((String) r.get("useruuid"), (String) r.get("name"));
        return out;
    }

    private Map<String, Set<String>> currentClients(Set<String> uuids, LocalDate from, LocalDate to) {
        Map<String, Set<String>> out = new LinkedHashMap<>();
        @SuppressWarnings("unchecked")
        List<Tuple> rows = em.createNativeQuery("""
                SELECT cc.useruuid, cl.name AS client_name
                FROM contract_consultants cc
                JOIN contracts c ON c.uuid = cc.contractuuid
                LEFT JOIN client cl ON cl.uuid = c.clientuuid
                WHERE cc.useruuid IN (:uuids)
                  AND cc.activefrom <= :to AND cc.activeto >= :from
                  AND c.status IN ('SIGNED', 'TIME')
                ORDER BY cl.name
                """, Tuple.class)
                .setParameter("uuids", uuids)
                .setParameter("from", from)
                .setParameter("to", to)
                .getResultList();
        for (Tuple r : rows) {
            String name = (String) r.get("client_name");
            if (name != null) out.computeIfAbsent((String) r.get("useruuid"), k -> new TreeSet<>()).add(name);
        }
        return out;
    }

    /** Juniors whose orlov (maternity, unpaid or paid leave) covers most working days of the window. */
    private Set<String> onLeave(Set<String> uuids, LocalDate from, LocalDate to) {
        int workingDays = HourlyTeamDashboardService.workingDays(from, to).size();
        Set<String> out = new LinkedHashSet<>();
        if (workingDays == 0) return out;
        @SuppressWarnings("unchecked")
        List<Tuple> rows = em.createNativeQuery("""
                SELECT fud.useruuid AS user_id, COUNT(*) AS leave_days
                FROM fact_user_day fud
                WHERE fud.useruuid IN (:uuids)
                  AND fud.document_date >= :from AND fud.document_date <= :to
                  AND (COALESCE(fud.maternity_leave_hours, 0) > 0
                       OR COALESCE(fud.non_payd_leave_hours, 0) > 0
                       OR COALESCE(fud.paid_leave_hours, 0) > 0)
                GROUP BY fud.useruuid
                """, Tuple.class)
                .setParameter("uuids", uuids)
                .setParameter("from", from)
                .setParameter("to", to)
                .getResultList();
        for (Tuple r : rows) {
            double leaveDays = ((Number) r.get("leave_days")).doubleValue();
            if (leaveDays / workingDays >= ON_LEAVE_SHARE) out.add((String) r.get("user_id"));
        }
        return out;
    }
}
