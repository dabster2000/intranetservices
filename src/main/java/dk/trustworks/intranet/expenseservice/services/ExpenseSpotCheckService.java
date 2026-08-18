package dk.trustworks.intranet.expenseservice.services;

import dk.trustworks.intranet.expenseservice.model.Expense;
import dk.trustworks.intranet.expenseservice.model.ExpenseStateDeriver;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.persistence.EntityManager;
import jakarta.persistence.Query;
import jakarta.transaction.Transactional;
import jakarta.ws.rs.BadRequestException;
import jakarta.ws.rs.NotFoundException;
import lombok.extern.jbosslog.JBossLog;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;

/**
 * W5: the spot-check replaces the pre-payout gate. A deterministic sample
 * ({@code spot_check_sample_pct}, uuid-hash based so the set is stable within a
 * window) of auto-cleared items — soft-flagged AI approvals and AI-accepted
 * justifications — is surfaced for retrospective review.
 *
 * <p>Decisions: CLEAR is informational (audit row only). REJECT is a real reject
 * while the row is still pre-upload; once a voucher exists the entity hook
 * re-derives state from the pipeline status (the P0 lesson), so the item can only
 * be flagged for follow-up ({@code SPOT_CHECK_FLAGGED} in the decision log).
 */
@JBossLog
@ApplicationScoped
public class ExpenseSpotCheckService {

    public enum Outcome { CLEARED, REJECTED, FLAGGED }

    @Inject AIConfigSnapshot config;
    @Inject EntityManager em;
    @Inject ExpenseDecisionLogService logs;

    /** The current spot-check sample, newest auto-cleared first. */
    public List<Expense> sample() {
        int pct = config.getIntParameter("spot_check_sample_pct", 10);
        int windowDays = config.getIntParameter("spot_check_window_days", 7);
        Query q = em.createNativeQuery(
            "SELECT e.* FROM expenses e " +
            "WHERE e.soft_flags IS NOT NULL AND e.soft_flags <> '[]' " +
            "  AND e.status <> 'DELETED' " +
            "  AND e.state IN ('APPROVED','POSTING','POSTED','BOOKED') " +
            "  AND EXISTS (SELECT 1 FROM expense_decision_log dl " +
            "              WHERE dl.expense_uuid = e.uuid " +
            "                AND dl.action IN ('AI_VALIDATED_APPROVED','AI_ACCEPTED_JUSTIFICATION') " +
            "                AND dl.occurred_at >= :since) " +
            "  AND NOT EXISTS (SELECT 1 FROM expense_decision_log dl2 " +
            "              WHERE dl2.expense_uuid = e.uuid " +
            "                AND dl2.action IN ('SPOT_CHECK_CLEARED','SPOT_CHECK_FLAGGED','HR_REJECTED')) " +
            "  AND MOD(CRC32(e.uuid), 100) < :pct " +
            "ORDER BY e.datemodified DESC",
            Expense.class);
        q.setParameter("since", LocalDateTime.now().minusDays(windowDays));
        q.setParameter("pct", pct);
        @SuppressWarnings("unchecked")
        List<Expense> rows = q.getResultList();
        return rows;
    }

    /**
     * Decide one sampled item. CLEAR → audit row. REJECT → real reject while the row
     * is pre-upload; otherwise an audited follow-up flag (returning FLAGGED so the UI
     * can say what actually happened).
     */
    @Transactional
    public Outcome decide(String uuid, String actorUuid, boolean reject, String reason) {
        Expense e = Expense.findById(uuid);
        if (e == null) throw new NotFoundException();
        if (!reject) {
            logs.recordSpotCheckCleared(e, actorUuid);
            return Outcome.CLEARED;
        }
        if (reason == null || reason.isBlank()) {
            throw new BadRequestException("a spot-check reject needs a reason");
        }
        if (canHardReject(e.getStatus(), e.getVouchernumber())) {
            logs.recordHRReject(e, actorUuid, "Spot-check: " + reason);
            e.setStatus("DELETED");
            e.setState(ExpenseStateDeriver.REJECTED);
            e.setAttentionOwner(null);
            e.setAttentionKind(null);
            e.setDatemodified(LocalDate.now());
            return Outcome.REJECTED;
        }
        logs.recordSpotCheckFlagged(e, actorUuid, reason);
        return Outcome.FLAGGED;
    }

    /**
     * A hard reject is only safe while nothing exists in e-conomic: no voucher and a
     * pre-upload status. Package-private static for plain unit tests.
     */
    static boolean canHardReject(String status, int vouchernumber) {
        if (vouchernumber > 0) return false;
        return "CREATED".equals(status) || "VALIDATED".equals(status);
    }

    /** Per-employee digest of auto-cleared items in the window (simple admin view first). */
    public List<Object[]> digestRows(int days) {
        Query q = em.createNativeQuery(
            "SELECT e.useruuid, " +
            "       COUNT(*) AS autoCleared, " +
            "       COALESCE(SUM(e.amount), 0) AS totalAmount, " +
            "       SUM(CASE WHEN e.soft_flags IS NOT NULL AND e.soft_flags <> '[]' THEN 1 ELSE 0 END) AS softFlagged, " +
            "       SUM(CASE WHEN EXISTS (SELECT 1 FROM expense_decision_log dj " +
            "                             WHERE dj.expense_uuid = e.uuid AND dj.action = 'AI_ACCEPTED_JUSTIFICATION') THEN 1 ELSE 0 END) AS aiAcceptedJustifications, " +
            "       SUM(CASE WHEN EXISTS (SELECT 1 FROM expense_decision_log df " +
            "                             WHERE df.expense_uuid = e.uuid AND df.action = 'SPOT_CHECK_FLAGGED') THEN 1 ELSE 0 END) AS spotCheckFlags " +
            "FROM expenses e " +
            "WHERE e.status <> 'DELETED' " +
            "  AND EXISTS (SELECT 1 FROM expense_decision_log dl " +
            "              WHERE dl.expense_uuid = e.uuid " +
            "                AND dl.action IN ('AI_VALIDATED_APPROVED','AI_ACCEPTED_JUSTIFICATION') " +
            "                AND dl.occurred_at >= :since) " +
            "GROUP BY e.useruuid " +
            "ORDER BY autoCleared DESC");
        q.setParameter("since", LocalDateTime.now().minusDays(days));
        @SuppressWarnings("unchecked")
        List<Object[]> rows = q.getResultList();
        return rows;
    }
}
