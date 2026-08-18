package dk.trustworks.intranet.expenseservice.events;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import dk.trustworks.intranet.apis.openai.OpenAIService;
import dk.trustworks.intranet.expenseservice.model.Expense;
import dk.trustworks.intranet.expenseservice.model.ExpenseStateDeriver;
import dk.trustworks.intranet.expenseservice.services.AIConfigSnapshot;
import dk.trustworks.intranet.expenseservice.services.AIConfigSnapshot.RuleView;
import dk.trustworks.intranet.expenseservice.services.ExpenseDecisionLogService;
import io.quarkus.vertx.ConsumeEvent;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.persistence.EntityManager;
import jakarta.persistence.Query;
import jakarta.transaction.Transactional;
import lombok.extern.jbosslog.JBossLog;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;

/**
 * W2: closes the justification loop. After an employee submits a justification the
 * expense already sits with the controller (the endpoint's synchronous hand-off);
 * this consumer runs ONE cheap OpenAI pass judging whether the written reason
 * plausibly addresses the fired rule.
 *
 * <ul>
 *   <li>Accept (confidence ≥ threshold, no guardrail) → auto-approve: decision-log
 *       {@code AI_ACCEPTED_JUSTIFICATION}, soft flag for the W5 spot-check,
 *       CREATED → VALIDATED, state APPROVED.</li>
 *   <li>Refer → the item stays with the controller; the AI's one-line reservation is
 *       recorded as {@code AI_REFERRED_JUSTIFICATION} (surfaced in the review panel's
 *       decision-log timeline).</li>
 *   <li>Guardrails always refer without calling the AI: amount ≥
 *       {@code justification_always_human_dkk}; same employee + same rule ≥
 *       {@code justification_repeat_fires_threshold} fires in
 *       {@code justification_repeat_window_days}; rules marked {@code always_human};
 *       unknown rule. Any AI error also refers (fail closed).</li>
 * </ul>
 *
 * The OpenAI call runs outside any JTA transaction (same split as
 * {@link ExpenseCreatedConsumer}: slow AI work unheld, fast transactional writes).
 */
@JBossLog
@ApplicationScoped
public class ExpenseJustificationAiConsumer {

    private static final ObjectMapper MAPPER = new ObjectMapper();
    static final String SOFT_FLAG_AI_ACCEPTED_JUSTIFICATION = "AI_ACCEPTED_JUSTIFICATION";

    @Inject AIConfigSnapshot config;
    @Inject OpenAIService openAIService;
    @Inject ExpenseDecisionLogService decisionLogs;
    @Inject EntityManager em;

    /** The AI's verdict on one justification. */
    record Verdict(boolean accept, double confidence, String reservation) {}

    /** Facts read in the first transaction, judged outside it. */
    record Facts(String uuid, String useruuid, double amount, String ruleId,
                 String ruleDisplayName, String ruleDescription,
                 String merchant, LocalDate expenseDate,
                 String justification, String aiRejectionReason, int repeatFires) {}

    @ConsumeEvent(value = "expense.justification.review", blocking = true)
    public void onJustificationSubmitted(String expenseUuid) {
        try {
            review(expenseUuid);
        } catch (Exception e) {
            // Fail closed: the row already sits with the controller.
            log.errorf(e, "Justification AI review failed for %s — item stays with the controller", expenseUuid);
        }
    }

    void review(String expenseUuid) {
        Facts facts = loadFacts(expenseUuid);
        if (facts == null) return; // no longer an eligible justification item

        RuleView rule = facts.ruleId() == null ? null : config.getRule(facts.ruleId());
        String guard = guardrailReason(
                facts.amount(),
                config.getDecimalParameter("justification_always_human_dkk", new BigDecimal("1000")).doubleValue(),
                facts.repeatFires(),
                config.getIntParameter("justification_repeat_fires_threshold", 3),
                config.getIntParameter("justification_repeat_window_days", 90),
                rule);
        if (guard != null) {
            log.infof("Justification for %s kept with the controller (guardrail: %s)", expenseUuid, guard);
            recordReferral(expenseUuid, "Guardrail: " + guard);
            return;
        }

        Verdict verdict = judge(facts); // OpenAI — no transaction held
        if (verdict == null) {
            recordReferral(expenseUuid, "AI review unavailable — kept with a human (fail closed)");
            return;
        }

        double minConfidence = config.getDecimalParameter(
                "justification_min_confidence", new BigDecimal("0.70")).doubleValue();
        if (verdict.accept() && verdict.confidence() >= minConfidence) {
            applyAccept(expenseUuid, verdict);
        } else {
            String why = verdict.accept()
                    ? "AI accepted but below the confidence floor (" + verdict.confidence() + ")"
                    : (verdict.reservation() == null || verdict.reservation().isBlank()
                        ? "AI referred the justification" : verdict.reservation());
            recordReferral(expenseUuid, why);
        }
    }

    /** Eligibility read + repeat-fires count, one fast transaction. Null = not eligible. */
    @Transactional
    Facts loadFacts(String expenseUuid) {
        Expense e = Expense.findById(expenseUuid);
        if (e == null) return null;
        if (!ExpenseStateDeriver.NEEDS_ATTENTION.equals(e.getState())
                || !ExpenseStateDeriver.OWNER_ACCOUNTING.equals(e.getAttentionOwner())
                || !ExpenseStateDeriver.KIND_POLICY.equals(e.getAttentionKind())
                || Boolean.TRUE.equals(e.getFinanceReviewOnly())
                || e.getEmployeeJustification() == null
                || e.getEmployeeJustification().isBlank()) {
            return null;
        }

        int windowDays = config.getIntParameter("justification_repeat_window_days", 90);
        int repeatFires = 0;
        if (e.getAiRuleId() != null && e.getUseruuid() != null) {
            Query q = em.createNativeQuery(
                "SELECT COUNT(*) FROM expense_decision_log dl " +
                "JOIN expenses ex ON ex.uuid = dl.expense_uuid " +
                "WHERE dl.action = 'AI_VALIDATED_REJECTED' AND dl.ai_rule_id = :rule " +
                "  AND ex.useruuid = :user AND dl.occurred_at >= :since");
            q.setParameter("rule", e.getAiRuleId());
            q.setParameter("user", e.getUseruuid());
            q.setParameter("since", LocalDateTime.now().minusDays(windowDays));
            repeatFires = ((Number) q.getSingleResult()).intValue();
        }

        RuleView rule = e.getAiRuleId() == null ? null : config.getRule(e.getAiRuleId());
        return new Facts(e.getUuid(), e.getUseruuid(),
                e.getAmount() == null ? 0.0 : e.getAmount(),
                e.getAiRuleId(),
                rule == null ? null : rule.displayName(),
                rule == null ? null : rule.description(),
                e.getExtractedMerchantName(), e.getExpensedate(),
                e.getEmployeeJustification(), e.getAiValidationReason(), repeatFires);
    }

    /**
     * Pure guardrail decision — null when the AI may judge, else the human-readable
     * reason the item must stay with a person. Package-private static for unit tests.
     */
    static String guardrailReason(double amount, double alwaysHumanDkk,
                                  int repeatFires, int repeatThreshold, int windowDays,
                                  RuleView rule) {
        if (amount >= alwaysHumanDkk) {
            return "amount " + (long) amount + " DKK ≥ " + (long) alwaysHumanDkk + " DKK";
        }
        if (repeatFires >= repeatThreshold) {
            return "same rule fired " + repeatFires + "× for this employee in " + windowDays + " days";
        }
        if (rule == null) {
            return "no configured rule for this item";
        }
        if (rule.alwaysHuman()) {
            return "rule " + rule.ruleId() + " is marked always-human";
        }
        return null;
    }

    /** One cheap OpenAI pass. Null on any failure (caller refers — fail closed). */
    Verdict judge(Facts f) {
        try {
            String system = config.getPromptBody("JUSTIFICATION_REVIEW");
            if (system == null || system.isBlank()) {
                log.warn("JUSTIFICATION_REVIEW prompt template missing — referring to a human");
                return null;
            }
            String rendered = f.ruleId() == null ? null : config.renderedRuleDescription(f.ruleId());
            String description = rendered != null ? rendered
                    : (f.ruleDescription() == null ? "" : f.ruleDescription());
            StringBuilder user = new StringBuilder();
            user.append("Fired rule: ").append(f.ruleId());
            if (f.ruleDisplayName() != null) user.append(" — ").append(f.ruleDisplayName());
            user.append('\n');
            if (!description.isBlank()) user.append("Rule: ").append(description).append('\n');
            if (f.aiRejectionReason() != null && !f.aiRejectionReason().isBlank()) {
                user.append("Why the AI blocked it: ").append(f.aiRejectionReason()).append('\n');
            }
            user.append("Amount: ").append(f.amount()).append(" DKK\n");
            if (f.expenseDate() != null) user.append("Expense date: ").append(f.expenseDate()).append('\n');
            if (f.merchant() != null && !f.merchant().isBlank()) {
                user.append("Merchant: ").append(f.merchant()).append('\n');
            }
            user.append("\nEmployee justification:\n").append(f.justification());
            user.append("\n\nDoes this justification plausibly address the fired rule? Return the JSON verdict.");

            // store=false: justification text is employee data — never retained by OpenAI.
            String response = openAIService.askQuestionWithSchema(
                    system, user.toString(), buildVerdictSchema(), "JustificationReviewResult",
                    "{\"accept\":false,\"confidence\":0.0,\"reservation\":\"AI refused or failed\"}",
                    null, 0, false);
            return parseVerdict(response);
        } catch (Exception e) {
            log.warnf(e, "Justification AI call failed for %s", f.uuid());
            return null;
        }
    }

    static ObjectNode buildVerdictSchema() {
        ObjectNode schema = MAPPER.createObjectNode();
        schema.put("type", "object");
        schema.put("additionalProperties", false);
        ObjectNode props = schema.putObject("properties");
        props.putObject("accept").put("type", "boolean");
        props.putObject("confidence").put("type", "number");
        props.putObject("reservation").put("type", "string")
             .put("description", "One line: what a human should double-check, or why the justification fails. Empty when fully convincing.");
        schema.putArray("required").add("accept").add("confidence").add("reservation");
        return schema;
    }

    /** Package-private static for unit tests. Null on unparsable/absent fields. */
    static Verdict parseVerdict(String json) {
        if (json == null || json.isBlank()) return null;
        try {
            JsonNode root = MAPPER.readTree(json);
            if (!root.has("accept") || !root.has("confidence")) return null;
            double confidence = root.path("confidence").asDouble(0.0);
            if (confidence < 0.0 || confidence > 1.0) confidence = 0.0;
            return new Verdict(root.path("accept").asBoolean(false), confidence,
                    root.path("reservation").asText(""));
        } catch (Exception e) {
            return null;
        }
    }

    @Transactional
    void applyAccept(String expenseUuid, Verdict verdict) {
        Expense e = Expense.findById(expenseUuid);
        if (e == null || !stillEligible(e)) return; // decided by a human in the meantime
        String reason = verdict.reservation() == null || verdict.reservation().isBlank()
                ? "AI accepted the justification (confidence " + verdict.confidence() + ")"
                : "AI accepted the justification (confidence " + verdict.confidence() + "): " + verdict.reservation();
        decisionLogs.recordAIAcceptedJustification(e, reason);
        // Same approve semantics as ExpenseReviewDecisionService.approve — soft-flagged
        // so the W5 spot-check samples it.
        e.setSoftFlags(appendSoftFlag(e.getSoftFlags(), SOFT_FLAG_AI_ACCEPTED_JUSTIFICATION));
        if ("CREATED".equals(e.getStatus())) {
            e.setStatus("VALIDATED");
        }
        e.setState(ExpenseStateDeriver.APPROVED);
        e.setAttentionOwner(null);
        e.setAttentionKind(null);
        e.setDatemodified(LocalDate.now());
        log.infof("Justification for %s ACCEPTED by AI — auto-approved (soft-flagged)", expenseUuid);
    }

    @Transactional
    void recordReferral(String expenseUuid, String reservation) {
        Expense e = Expense.findById(expenseUuid);
        if (e == null || !stillEligible(e)) return;
        decisionLogs.recordAIReferredJustification(e, reservation);
    }

    private static boolean stillEligible(Expense e) {
        return ExpenseStateDeriver.NEEDS_ATTENTION.equals(e.getState())
                && ExpenseStateDeriver.OWNER_ACCOUNTING.equals(e.getAttentionOwner())
                && ExpenseStateDeriver.KIND_POLICY.equals(e.getAttentionKind());
    }

    /** Append a flag to the JSON-array soft_flags column, deduplicated. Pure; unit-tested. */
    static String appendSoftFlag(String softFlagsJson, String flag) {
        try {
            var arr = softFlagsJson == null || softFlagsJson.isBlank()
                    ? MAPPER.createArrayNode()
                    : (com.fasterxml.jackson.databind.node.ArrayNode) MAPPER.readTree(softFlagsJson);
            for (JsonNode n : arr) {
                if (flag.equals(n.asText())) return arr.toString();
            }
            arr.add(flag);
            return arr.toString();
        } catch (Exception e) {
            return "[\"" + flag + "\"]";
        }
    }
}
