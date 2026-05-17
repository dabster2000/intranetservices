package dk.trustworks.intranet.expenseservice.resources;

import dk.trustworks.intranet.expenseservice.dto.RuleFiringStatsDTO;
import dk.trustworks.intranet.expenseservice.dto.RuleFiringStatsDTO.Entry;
import jakarta.annotation.security.RolesAllowed;
import jakarta.persistence.EntityManager;
import jakarta.persistence.Query;
import jakarta.ws.rs.*;
import jakarta.ws.rs.core.MediaType;

import java.time.LocalDateTime;
import java.util.List;

@Path("/admin/rules/firing-stats")
@Produces(MediaType.APPLICATION_JSON)
@RolesAllowed({"admin:write"})
public class RuleFiringStatsResource {

    private final EntityManager em;

    public RuleFiringStatsResource(EntityManager em) {
        this.em = em;
    }

    @GET
    public RuleFiringStatsDTO list(@QueryParam("days") @DefaultValue("30") int days) {
        LocalDateTime from = LocalDateTime.now().minusDays(days);
        Query q = em.createNativeQuery(
            "SELECT ai_rule_id, COUNT(*) AS firings, MAX(occurred_at) " +
            "FROM expense_decision_log " +
            "WHERE ai_rule_id IS NOT NULL AND occurred_at >= :fromTs " +
            "GROUP BY ai_rule_id " +
            "ORDER BY firings DESC"
        );
        q.setParameter("fromTs", from);
        @SuppressWarnings("unchecked")
        List<Object[]> rows = q.getResultList();
        List<Entry> entries = rows.stream().map(r -> new Entry(
            (String) r[0],
            ((Number) r[1]).intValue(),
            r[2] == null ? null : ((java.sql.Timestamp) r[2]).toLocalDateTime()
        )).toList();
        return new RuleFiringStatsDTO(entries);
    }
}
