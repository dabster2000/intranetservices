package dk.trustworks.intranet.expenseservice.resources;

import dk.trustworks.intranet.expenseservice.dto.RuleOverrideStatsDTO;
import dk.trustworks.intranet.expenseservice.dto.RuleOverrideStatsDTO.Entry;
import jakarta.annotation.security.RolesAllowed;
import jakarta.persistence.EntityManager;
import jakarta.persistence.Query;
import jakarta.ws.rs.*;
import jakarta.ws.rs.core.MediaType;

import java.time.LocalDateTime;
import java.util.List;

/**
 * Per-rule override rate: how often a human later approved an expense the AI
 * blocked on that rule. An override rate near 1.0 means the rule only creates
 * review work — every block gets waved through — and is a candidate for
 * SOFT_FLAG. Feeds the W1 rule-catalog calibration in the Policy Library.
 */
@Path("/admin/rules/override-stats")
@Produces(MediaType.APPLICATION_JSON)
@RolesAllowed({"admin:write"})
public class RuleOverrideStatsResource {

    private final EntityManager em;

    public RuleOverrideStatsResource(EntityManager em) {
        this.em = em;
    }

    @GET
    public RuleOverrideStatsDTO list(@QueryParam("days") @DefaultValue("180") int days) {
        LocalDateTime from = LocalDateTime.now().minusDays(days);
        Query q = em.createNativeQuery(
            "SELECT rej.ai_rule_id, " +
            "       COUNT(DISTINCT rej.uuid) AS firings, " +
            "       COUNT(DISTINCT rej.expense_uuid) AS blocked_expenses, " +
            "       COUNT(DISTINCT app.expense_uuid) AS overridden_expenses, " +
            "       MAX(rej.occurred_at) AS last_fired " +
            "FROM expense_decision_log rej " +
            "LEFT JOIN expense_decision_log app " +
            "  ON app.expense_uuid = rej.expense_uuid " +
            " AND app.action = 'HR_APPROVED' " +
            " AND app.occurred_at > rej.occurred_at " +
            "WHERE rej.action = 'AI_VALIDATED_REJECTED' " +
            "  AND rej.ai_rule_id IS NOT NULL " +
            "  AND rej.occurred_at >= :fromTs " +
            "GROUP BY rej.ai_rule_id " +
            "ORDER BY blocked_expenses DESC"
        );
        q.setParameter("fromTs", from);
        @SuppressWarnings("unchecked")
        List<Object[]> rows = q.getResultList();
        List<Entry> entries = rows.stream().map(r -> Entry.of(
            (String) r[0],
            ((Number) r[1]).intValue(),
            ((Number) r[2]).intValue(),
            ((Number) r[3]).intValue(),
            r[4] == null ? null : (LocalDateTime) r[4]
        )).toList();
        return new RuleOverrideStatsDTO(days, entries);
    }
}
