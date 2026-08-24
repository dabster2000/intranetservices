package dk.trustworks.intranet.vacationservice.services;

import dk.trustworks.intranet.vacationservice.engine.EmploymentCompanyResolver;
import dk.trustworks.intranet.vacationservice.engine.EmploymentCompanyResolver.StatusFact;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.persistence.EntityManager;
import lombok.extern.jbosslog.JBossLog;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;

/**
 * The one place the Danløn import learns where people were employed.
 *
 * <p>It reads {@code userstatus} directly, in one query, for exactly the users
 * a batch resolved to. It deliberately does <em>not</em> go through
 * {@code User} — see {@link EmploymentCompanyResolver} for the trap that
 * route carries — and it deliberately does not reuse
 * {@code UserService.findUsersByDateAndStatusListAndTypesAndCompany}, whose
 * CTE filters on the company <em>before</em> ranking by date and therefore
 * answers "their latest status within that company", not "which company are
 * they in".</p>
 *
 * <p>Cost: one index-only read. {@code idx} on
 * {@code (useruuid, statusdate DESC, companyuuid, type)} from
 * {@code V83__Work_performance_optimizations.sql} covers the predicate and
 * every selected column. Hydrating the users instead would cost eight bulk
 * queries and pull every employee's salary and bank details into heap on each
 * CSV upload.</p>
 */
@JBossLog
@ApplicationScoped
public class EmploymentCompanyLookup {

    @Inject
    EntityManager em;

    /**
     * @return company uuid per user, for the users whose timeline can answer.
     * A user absent from the returned map has no status at or before
     * {@code asOf}, or one that carries no company — the caller must treat
     * that as unknown, never as "same company".
     */
    public Map<String, String> companiesAt(Collection<String> useruuids, LocalDate asOf) {
        if (useruuids == null || useruuids.isEmpty() || asOf == null) return Map.of();
        List<String> ids = new ArrayList<>(new LinkedHashSet<>(useruuids));

        // Scalar projection, not entities: UserStatus.company is an EAGER
        // @ManyToOne, so loading the rows as entities would drag Company
        // objects along for a field we only ever compare by uuid.
        List<Object[]> statusRows = em.createQuery(
                        "SELECT s.useruuid, s.statusdate, s.company.uuid FROM UserStatus s "
                                + "WHERE s.useruuid IN :ids AND s.statusdate <= :asOf", Object[].class)
                .setParameter("ids", ids)
                .setParameter("asOf", asOf)
                .getResultList();

        Map<String, List<StatusFact>> timelineByUser = new HashMap<>();
        for (Object[] statusRow : statusRows) {
            timelineByUser.computeIfAbsent((String) statusRow[0], k -> new ArrayList<>())
                    .add(new StatusFact((LocalDate) statusRow[1], (String) statusRow[2]));
        }

        Map<String, String> companyByUser = new HashMap<>();
        timelineByUser.forEach((useruuid, timeline) -> EmploymentCompanyResolver.companyAt(timeline, asOf)
                .ifPresent(companyuuid -> companyByUser.put(useruuid, companyuuid)));
        log.debugf("vacation-import: resolved the employment company of %d/%d users as of %s",
                companyByUser.size(), ids.size(), asOf);
        return companyByUser;
    }
}
