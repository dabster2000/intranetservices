package dk.trustworks.intranet.aggregates.invoice.services;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.persistence.EntityManager;
import lombok.extern.jbosslog.JBossLog;

import java.time.LocalDate;
import java.util.Collection;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Resolves each user's company UUID as-of a given date, using the `userstatus` table.
 * Extracted from {@code InvoiceService} (legacy inline native query at l.700-716) so
 * that the attribution-driven internal-invoice generator and the cascade regeneration
 * path can reuse the same lookup without duplication.
 *
 * <p>The query uses a window function {@code ROW_NUMBER() OVER (PARTITION BY useruuid
 * ORDER BY statusdate DESC)} to pick each user's most recent status row at or before
 * {@code asOf}. This matches the semantics of the legacy consultant-filter used by
 * {@code autoCreateAndQueueInternal} and the cross-company detection logic.
 *
 * <p><b>Security:</b> all user inputs are bound via JPA named parameters — no string
 * concatenation into SQL. This addresses the project memory flag on SQL injection
 * risk in legacy {@code InvoiceService} native queries.
 */
@JBossLog
@ApplicationScoped
public class UserCompanyResolver {

    @Inject
    EntityManager em;

    /**
     * Resolves the company UUID for each supplied user UUID as of the given date.
     *
     * @param userUuids the consultant/user UUIDs to resolve. Null or empty input
     *                  returns an empty map without touching the database.
     * @param asOf      the point in time to resolve status against. The most recent
     *                  {@code userstatus} row with {@code statusdate <= asOf} wins.
     * @return an immutable-style map from user UUID to resolved company UUID.
     *         Users without a status row at or before {@code asOf} are absent from
     *         the map (the caller should treat missing entries as "unresolved" and
     *         skip them in cross-company comparisons).
     */
    @SuppressWarnings("unchecked")
    public Map<String, String> resolveCompanies(Collection<String> userUuids, LocalDate asOf) {
        if (userUuids == null || userUuids.isEmpty()) {
            return Collections.emptyMap();
        }
        if (asOf == null) {
            throw new IllegalArgumentException("asOf must not be null");
        }

        // Parameter-bound native SQL: :users expands the collection, :asOf binds the date.
        // NEVER concatenate collection contents into the SQL literal.
        String sql = """
                SELECT u.useruuid, u.companyuuid
                FROM (
                    SELECT useruuid, companyuuid,
                           ROW_NUMBER() OVER (PARTITION BY useruuid ORDER BY statusdate DESC) AS rn
                    FROM userstatus
                    WHERE statusdate <= :asOf
                      AND useruuid IN (:users)
                ) u
                WHERE u.rn = 1
                """;

        List<Object[]> rows = em.createNativeQuery(sql)
                .setParameter("users", userUuids)
                .setParameter("asOf", asOf)
                .getResultList();

        Map<String, String> result = new HashMap<>(rows.size() * 2);
        for (Object[] row : rows) {
            String userUuid = (String) row[0];
            String companyUuid = (String) row[1];
            if (userUuid != null && companyUuid != null) {
                result.put(userUuid, companyUuid);
            }
        }
        return result;
    }
}
