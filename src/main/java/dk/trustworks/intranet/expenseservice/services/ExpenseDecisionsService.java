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

        appendOutcomeFilter(where, outcomes);
        if (employeeUuid != null && !employeeUuid.isBlank()) {
            where.append("AND e.useruuid = :empUuid ");
            params.put("empUuid", employeeUuid);
        }

        // SELECT columns: indices 0-11.
        //   r[0]  = l.uuid
        //   r[1]  = l.expense_uuid
        //   r[2]  = l.occurred_at
        //   r[3]  = l.action
        //   r[4]  = l.to_review_state
        //   r[5]  = l.ai_rule_id
        //   r[6]  = l.reason_text
        //   r[7]  = e.useruuid
        //   r[8]  = merchant display value: extracted merchant, fallback description
        //   r[9]  = e.amount
        //   r[10] = u.firstname
        //   r[11] = u.lastname
        String sql =
                "SELECT l.uuid, l.expense_uuid, l.occurred_at, l.action, l.to_review_state, " +
                "       l.ai_rule_id, l.reason_text, " +
                "       e.useruuid, COALESCE(NULLIF(e.extracted_merchant_name, ''), e.description), e.amount, " +
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
                        (java.time.LocalDateTime) r[2],
                        new ExpenseDecisionRowDTO.EmployeeStub(
                                (String) r[7],
                                joinName(r[10], r[11])),
                        (String) r[8],
                        parseAmount(r[9]),
                        null,                                               // perPersonDkk: V351-dependent
                        mapOutcome((String) r[3], (String) r[4]),
                        r[5] == null ? List.of() : List.of((String) r[5]),
                        (String) r[6]))
                .toList();

        int totalCount = countMatching(where.toString(), params);
        ExpenseDecisionsSummaryDTO summary = computeSummary(from, to);
        return new ExpenseDecisionsResponseDTO(decisions, totalCount, summary);
    }

    private static double parseAmount(Object v) {
        if (v == null) return 0.0;
        if (v instanceof Number n) return n.doubleValue();
        try { return Double.parseDouble(v.toString()); }
        catch (NumberFormatException e) { return 0.0; }
    }

    private ExpenseDecisionsSummaryDTO computeSummary(LocalDate from, LocalDate to) {
        Query q = em.createNativeQuery(
                "SELECT " +
                "  SUM(CASE WHEN action = 'AI_VALIDATED_APPROVED' THEN 1 ELSE 0 END), " +
                "  SUM(CASE WHEN to_review_state IN ('NEEDS_FIX', 'NEEDS_JUSTIFICATION', 'HR_SENT_BACK', 'AWAITING_EMPLOYEE_ACTION') THEN 1 ELSE 0 END), " +
                "  SUM(CASE WHEN to_review_state IN ('PENDING_HR', 'PENDING_HR_REVIEW') THEN 1 ELSE 0 END) " +
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

    private static void appendOutcomeFilter(StringBuilder where, List<String> outcomes) {
        if (outcomes == null || outcomes.isEmpty()) return;

        List<String> conditions = outcomes.stream()
                .filter(o -> o != null && !o.isBlank())
                .distinct()
                .map(ExpenseDecisionsService::outcomeCondition)
                .filter(c -> c != null && !c.isBlank())
                .toList();

        if (conditions.isEmpty()) {
            where.append("AND 1 = 0 ");
            return;
        }

        where.append("AND (")
                .append(String.join(" OR ", conditions))
                .append(") ");
    }

    private static String outcomeCondition(String outcome) {
        return switch (outcome) {
            case "APPROVED" -> APPROVED_CONDITION;
            case "AUTO_FIX" -> AUTO_FIX_CONDITION;
            case "EXPLAIN" -> EXPLAIN_CONDITION;
            case "REJECTED" -> REJECTED_CONDITION;
            case "OTHER" -> OTHER_CONDITION;
            default -> null;
        };
    }

    private static final String APPROVED_CONDITION =
            "l.action = 'AI_VALIDATED_APPROVED'";
    private static final String AUTO_FIX_CONDITION =
            "l.to_review_state = 'NEEDS_FIX'";
    private static final String EXPLAIN_CONDITION =
            "l.to_review_state IN ('NEEDS_JUSTIFICATION', 'AWAITING_EMPLOYEE_ACTION')";
    private static final String REJECTED_CONDITION =
            "l.to_review_state IN ('PENDING_HR', 'PENDING_HR_REVIEW')";
    private static final String OTHER_CONDITION =
            "(l.action IS NULL OR l.action <> 'AI_VALIDATED_APPROVED') " +
            "AND (l.to_review_state IS NULL OR l.to_review_state NOT IN (" +
            "'NEEDS_FIX', 'NEEDS_JUSTIFICATION', 'AWAITING_EMPLOYEE_ACTION', " +
            "'PENDING_HR', 'PENDING_HR_REVIEW'))";

    private static String mapOutcome(String action, String toReviewState) {
        if ("AI_VALIDATED_APPROVED".equals(action)) return "APPROVED";
        if ("NEEDS_FIX".equals(toReviewState)) return "AUTO_FIX";
        if ("NEEDS_JUSTIFICATION".equals(toReviewState)
                || "AWAITING_EMPLOYEE_ACTION".equals(toReviewState)) return "EXPLAIN";
        if ("PENDING_HR".equals(toReviewState)
                || "PENDING_HR_REVIEW".equals(toReviewState)) return "REJECTED";
        return "OTHER";
    }

    private static String joinName(Object first, Object last) {
        return ((first == null ? "" : first.toString()) + " " +
                (last == null ? "" : last.toString())).trim();
    }
}
