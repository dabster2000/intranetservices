package dk.trustworks.intranet.expenseservice.services;

import dk.trustworks.intranet.expenseservice.dto.ExpenseDecisionRowDTO;
import dk.trustworks.intranet.expenseservice.dto.ExpenseDecisionsResponseDTO;
import dk.trustworks.intranet.expenseservice.dto.ExpenseDecisionsSummaryDTO;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.persistence.EntityManager;
import jakarta.persistence.Query;

import java.time.LocalDate;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

@ApplicationScoped
public class ExpenseDecisionsService {

    private final EntityManager em;

    public ExpenseDecisionsService(EntityManager em) {
        this.em = em;
    }

    public ExpenseDecisionsResponseDTO list(
            LocalDate from,
            LocalDate to,
            List<String> outcomes,
            String employeeUuid,
            int limit,
            int offset) {

        StringBuilder where = new StringBuilder(
                "WHERE l.occurred_at >= :fromTs AND l.occurred_at < :toTs ");
        Map<String, Object> params = new HashMap<>();
        params.put("fromTs", from.atStartOfDay());
        params.put("toTs", to.plusDays(1).atStartOfDay());

        if (outcomes != null && !outcomes.isEmpty()) {
            where.append("AND l.action IN (:actions) ");
            params.put("actions", outcomes);
        }
        if (employeeUuid != null && !employeeUuid.isBlank()) {
            where.append("AND e.useruuid = :empUuid ");
            params.put("empUuid", employeeUuid);
        }

        // Phase 2: extracted_per_person_dkk is NOT selected here — column added by V351 in Task 3.1.
        // perPersonDkk is returned as null for all rows. Task 3.2 populates the column; Task 3.6 reads it.
        // SELECT has 11 columns: indices 0-10.
        //   r[0]  = l.uuid
        //   r[1]  = l.expense_uuid
        //   r[2]  = l.occurred_at
        //   r[3]  = l.action
        //   r[4]  = l.to_review_state
        //   r[5]  = l.ai_rule_id
        //   r[6]  = l.reason_text
        //   r[7]  = e.useruuid
        //   r[8]  = e.description
        //   r[9]  = e.amount
        //   r[10] = u.firstname
        //   r[11] = u.lastname
        String sql =
                "SELECT l.uuid, l.expense_uuid, l.occurred_at, l.action, l.to_review_state, " +
                "       l.ai_rule_id, l.reason_text, " +
                "       e.useruuid, e.description, e.amount, " +
                "       u.firstname, u.lastname " +
                "FROM expense_decision_log l " +
                "JOIN expenses e ON e.uuid = l.expense_uuid " +
                "LEFT JOIN user u ON u.uuid = e.useruuid " +
                where +
                "ORDER BY l.occurred_at DESC " +
                "LIMIT :lim OFFSET :off";

        Query q = em.createNativeQuery(sql);
        params.forEach(q::setParameter);
        q.setParameter("lim", limit);
        q.setParameter("off", offset);

        @SuppressWarnings("unchecked")
        List<Object[]> rows = q.getResultList();

        List<ExpenseDecisionRowDTO> decisions = rows.stream()
                .map(r -> new ExpenseDecisionRowDTO(
                        (String) r[0],
                        (String) r[1],
                        ((java.sql.Timestamp) r[2]).toLocalDateTime(),
                        new ExpenseDecisionRowDTO.EmployeeStub(
                                (String) r[7],
                                joinName(r[10], r[11])),
                        (String) r[8],
                        r[9] == null ? 0.0 : ((Number) r[9]).doubleValue(),
                        null,                                               // perPersonDkk: V351-dependent
                        mapOutcome((String) r[3], (String) r[4]),
                        r[5] == null ? List.of() : List.of((String) r[5]),
                        (String) r[6]))
                .toList();

        int totalCount = countMatching(where.toString(), params);
        ExpenseDecisionsSummaryDTO summary = computeSummary(from, to);
        return new ExpenseDecisionsResponseDTO(decisions, totalCount, summary);
    }

    private ExpenseDecisionsSummaryDTO computeSummary(LocalDate from, LocalDate to) {
        Query q = em.createNativeQuery(
                "SELECT " +
                "  SUM(CASE WHEN action = 'AI_VALIDATED_APPROVED' THEN 1 ELSE 0 END), " +
                "  SUM(CASE WHEN to_review_state = 'AWAITING_EMPLOYEE_ACTION' THEN 1 ELSE 0 END), " +
                "  SUM(CASE WHEN to_review_state = 'PENDING_HR_REVIEW' THEN 1 ELSE 0 END) " +
                "FROM expense_decision_log " +
                "WHERE occurred_at >= :fromTs AND occurred_at < :toTs");
        q.setParameter("fromTs", from.atStartOfDay());
        q.setParameter("toTs", to.plusDays(1).atStartOfDay());
        Object[] r = (Object[]) q.getSingleResult();
        return new ExpenseDecisionsSummaryDTO(
                r[0] == null ? 0 : ((Number) r[0]).intValue(),
                r[1] == null ? 0 : ((Number) r[1]).intValue(),
                r[2] == null ? 0 : ((Number) r[2]).intValue(),
                0.0);
    }

    private int countMatching(String where, Map<String, Object> params) {
        Query q = em.createNativeQuery(
                "SELECT COUNT(*) " +
                "FROM expense_decision_log l " +
                "JOIN expenses e ON e.uuid = l.expense_uuid " +
                where);
        params.forEach(q::setParameter);
        return ((Number) q.getSingleResult()).intValue();
    }

    private static String mapOutcome(String action, String toReviewState) {
        if ("AI_VALIDATED_APPROVED".equals(action)) return "APPROVED";
        if ("AWAITING_EMPLOYEE_ACTION".equals(toReviewState)) return "EXPLAIN";
        if ("PENDING_HR_REVIEW".equals(toReviewState)) return "REJECTED";
        return "OTHER";
    }

    private static String joinName(Object first, Object last) {
        return ((first == null ? "" : first.toString()) + " " +
                (last == null ? "" : last.toString())).trim();
    }
}
