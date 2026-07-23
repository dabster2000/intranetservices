package dk.trustworks.intranet.recruitmentservice.ai;

import dk.trustworks.intranet.model.Practice;
import dk.trustworks.intranet.recruitmentservice.ai.AiReferralTriagePrompts.Option;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.persistence.EntityManager;

import java.time.LocalDate;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * Lookup helpers for the P9 AI surfaces: the active-practice roster and
 * the <em>current</em> teamlead roster (temporal {@code teamroles} LEADER
 * window — the {@code RecruitmentVisibility.currentlyLedTeams} idiom
 * inverted to "all current leaders"). Used by
 * {@link AiReferralTriageReactor} to build the pick lists the model may
 * choose from, and by the pending-referrals read to re-validate stored
 * suggestions (a deactivated practice / departed teamlead nulls that
 * field at read time).
 */
@ApplicationScoped
public class RecruitmentAiDirectory {

    @Inject
    EntityManager em;

    /** Active practices in registry order, as (uuid, name) options. */
    public List<Option> activePractices() {
        return Practice.<Practice>list("active = true order by sortOrder").stream()
                .map(p -> new Option(p.getUuid(), p.getName()))
                .toList();
    }

    /**
     * All users who currently lead at least one team (DISTINCT temporal
     * LEADER rows), with display names — one query.
     */
    @SuppressWarnings("unchecked")
    public List<Option> currentTeamleads() {
        List<Object[]> rows = em.createNativeQuery("""
                        SELECT DISTINCT u.uuid, CONCAT(u.firstname, ' ', u.lastname)
                        FROM teamroles tr
                        JOIN user u ON u.uuid = tr.useruuid
                        WHERE tr.membertype = 'LEADER'
                          AND tr.startdate <= :today
                          AND (tr.enddate > :today OR tr.enddate IS NULL)
                        ORDER BY 2
                        """)
                .setParameter("today", LocalDate.now())
                .getResultList();
        return rows.stream()
                .map(r -> new Option((String) r[0], (String) r[1]))
                .toList();
    }

    /** The current-teamlead uuid set (validation twin of {@link #currentTeamleads()}). */
    public Set<String> currentTeamleadUuids() {
        return currentTeamleads().stream().map(Option::uuid).collect(Collectors.toSet());
    }

    /** Batched display names for arbitrary user uuids (pending-row enrichment). */
    @SuppressWarnings("unchecked")
    public Map<String, String> userNamesByUuid(Collection<String> userUuids) {
        if (userUuids == null || userUuids.isEmpty()) {
            return Map.of();
        }
        List<Object[]> rows = em.createNativeQuery("""
                        SELECT u.uuid, CONCAT(u.firstname, ' ', u.lastname)
                        FROM user u WHERE u.uuid IN :uuids
                        """)
                .setParameter("uuids", List.copyOf(userUuids))
                .getResultList();
        Map<String, String> names = new HashMap<>();
        for (Object[] row : rows) {
            names.put((String) row[0], (String) row[1]);
        }
        return names;
    }
}
