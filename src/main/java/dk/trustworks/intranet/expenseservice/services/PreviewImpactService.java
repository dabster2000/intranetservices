package dk.trustworks.intranet.expenseservice.services;

import dk.trustworks.intranet.expenseservice.dto.PreviewImpactRequestDTO;
import dk.trustworks.intranet.expenseservice.dto.PreviewImpactResponseDTO;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.persistence.EntityManager;
import jakarta.persistence.Query;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

@ApplicationScoped
public class PreviewImpactService {

    /**
     * Allow-list of parameter keys to the corresponding numeric column on `expenses`.
     * Adding new entries here lets new threshold parameters use the preview engine.
     * Unknown parameter keys return zero flips (preview unavailable).
     *
     * NOTE: column names are whitelisted here — never taken from user input directly —
     * so interpolating into the SQL string below is safe.
     */
    private static final Map<String, String> PARAM_TO_COLUMN = Map.of(
        "meal_cost_per_person_dkk",      "extracted_per_person_dkk",
        "it_equipment_pre_approval_dkk", "extracted_amount_dkk"
    );

    private final EntityManager em;

    public PreviewImpactService(EntityManager em) {
        this.em = em;
    }

    public PreviewImpactResponseDTO preview(PreviewImpactRequestDTO req) {
        String column = PARAM_TO_COLUMN.get(req.parameter());
        if (column == null) {
            // Unknown parameter — degrade gracefully, return zero flips
            return new PreviewImpactResponseDTO(0, 0, 0, List.of());
        }

        LocalDateTime from = LocalDateTime.now().minusDays(req.windowDays());

        // Count total AI-rejected decisions for this rule within the window.
        // NULL comparisons in SQL three-valued logic naturally exclude NULL column values.
        Query rejectedQ = em.createNativeQuery(
            "SELECT COUNT(*) FROM expense_decision_log l " +
            "JOIN expenses e ON e.uuid = l.expense_uuid " +
            "WHERE l.ai_rule_id = :ruleId " +
            "AND l.action <> 'AI_VALIDATED_APPROVED' " +
            "AND l.occurred_at >= :fromTs"
        );
        rejectedQ.setParameter("ruleId", req.ruleId());
        rejectedQ.setParameter("fromTs", from);
        int totalRejected = ((Number) rejectedQ.getSingleResult()).intValue();

        // Direction-aware range filter:
        //   Raise cap (newValue > oldValue): expenses whose stored value falls in (old, new]
        //   would no longer breach the threshold → flip to approved.
        //   Lower cap (newValue < oldValue): expenses whose stored value falls in [new, old)
        //   would newly breach the threshold → flip to rejected (i.e. remain rejected / newly reject).
        // For equal values no rows match either range → zero flips, which is correct.
        double lo = Math.min(req.oldValue(), req.newValue());
        double hi = Math.max(req.oldValue(), req.newValue());

        // Raise: rows strictly above old threshold but at or below new threshold flip TO approved.
        // Lower: rows at or above new threshold but strictly below old threshold flip TO rejected.
        String rangeFilter = req.newValue() > req.oldValue()
            ? "e." + column + " > :lo AND e." + column + " <= :hi"
            : "e." + column + " >= :lo AND e." + column + " < :hi";

        // `column` is sourced exclusively from PARAM_TO_COLUMN — safe to interpolate.
        Query flippedQ = em.createNativeQuery(
            "SELECT e.uuid FROM expense_decision_log l " +
            "JOIN expenses e ON e.uuid = l.expense_uuid " +
            "WHERE l.ai_rule_id = :ruleId " +
            "AND l.action <> 'AI_VALIDATED_APPROVED' " +
            "AND l.occurred_at >= :fromTs " +
            "AND " + rangeFilter + " " +
            "LIMIT 200"
        );
        flippedQ.setParameter("ruleId", req.ruleId());
        flippedQ.setParameter("fromTs", from);
        flippedQ.setParameter("lo", lo);
        flippedQ.setParameter("hi", hi);

        @SuppressWarnings("unchecked")
        List<String> flipped = flippedQ.getResultList();

        int wouldFlip = flipped.size();
        int wouldRemain = Math.max(0, totalRejected - wouldFlip);
        return new PreviewImpactResponseDTO(totalRejected, wouldFlip, wouldRemain, flipped);
    }
}
