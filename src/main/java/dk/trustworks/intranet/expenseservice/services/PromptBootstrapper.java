package dk.trustworks.intranet.expenseservice.services;

import dk.trustworks.intranet.expenseservice.model.AIPromptTemplate;
import io.quarkus.runtime.StartupEvent;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.event.Observes;
import jakarta.inject.Inject;
import jakarta.transaction.Transactional;
import lombok.extern.jbosslog.JBossLog;

import java.time.LocalDateTime;

/**
 * On startup, ensures the seeded AI prompt rows in {@code ai_prompt_template} have their real
 * bodies (verbatim from the original {@link ExpenseAIValidationService} code) instead of the
 * placeholder strings (__SEED_..._REPLACED_AT_BOOT__) inserted by Flyway V348.
 *
 * <p>After replacement, {@link AIConfigSnapshot#reload()} is invoked so the in-memory snapshot
 * sees the real prompts immediately.
 *
 * <p>Once the row body is anything other than the placeholder, this class is a no-op — admin edits
 * persisted via future tooling will not be overwritten on reboot.
 */
@JBossLog
@ApplicationScoped
public class PromptBootstrapper {

    @Inject
    AIConfigSnapshot snapshot;

    /**
     * Verbatim copy of the vision-extraction system prompt that lived inside
     * {@code ExpenseAIValidationService.extractExpenseData(...)}.
     */
    static final String VISION_EXTRACTION_PROMPT_BODY = """
                You are a receipt analysis assistant for expense validation.

                Analyze the attached receipt image and provide a COMPREHENSIVE description of everything visible.
                Extract ALL details that could be relevant for expense validation.
                Do NOT include information about layout, graphics, colors, fonts.

                Include the following information (if visible):

                **Basic Information:**
                - Receipt date (exact format as shown)
                - Store/merchant name
                - Store address (complete, as written)
                - Receipt number / transaction ID

                **Financial Details:**
                - Subtotal amount
                - Tax/VAT amount and percentage
                - Total amount (grand total including all taxes)
                - Currency
                - Payment method (cash, card, etc.)

                **Line Items (if visible):**
                - List all purchased items with quantities and prices
                - Item descriptions
                - Any discount items or promotions

                **Context Indicators:**
                - Number of guests / people (if indicated by items, quantities, or text like "for 2")
                - Time of purchase (if shown)
                - Any notes about the nature of purchase (business meeting, group meal, etc.)

                **Quality Assessment:**
                - Is the receipt readable and clear?
                - Are any parts blurry, cropped, or illegible?
                - Overall image quality

                **Suspicious Elements (if any):**
                - Altered or edited sections
                - Missing information that should be present
                - Unusual formatting or inconsistencies

                **Store Type Classification:**
                - Type of establishment (restaurant, cafe, electronics store, taxi service, etc.)
                - Expense category: food_drink, it_equipment, transportation, or other

                Provide the description as natural text with clear sections. Be thorough and objective.
                Do not make assumptions - only describe what is actually visible in the image.
                """;

    /**
     * Verbatim copy of {@code VALIDATION_SYSTEM_PROMPT} from {@code ExpenseAIValidationService},
     * followed by the rule-catalog section. The individual rule lines (previously rendered by
     * {@code buildRuleCatalogText()} iterating over {@code VALIDATION_RULES}) are represented by
     * the literal token {@code {{RULES_BLOCK}}}, which is substituted at call time by
     * {@link ExpenseAIValidationService#buildPolicyValidationPrompt()}.
     */
    static final String POLICY_VALIDATION_PROMPT_BODY = """
            You are an expense policy validator for Trustworks.

            You always:
            - Analyze the extracted receipt description carefully (date, merchant, address, line items, taxes, number of guests, etc.).
            - Use the structured context (expense record, user data, budgets, leave data, company office/home addresses).
            - Apply the validation rules from the catalog strictly and deterministically.
            - Use web search when needed to verify store locations, distances, or venue types.

            Severity & decision logic:
            - Each rule has severity in {OVERRIDE_APPROVE, REJECT, WARNING, INFO}.
            - For every rule, decide if it is FAILED, PASSED, or NOT_APPLICABLE.
            - If ANY rule with severity=OVERRIDE_APPROVE has decision=FAILED, the final decision MUST be approved,
              even if REJECT rules also FAILED.
            - Otherwise, if ANY rule with severity=REJECT has decision=FAILED, the final decision MUST be rejected.
            - Otherwise, the receipt is approved. WARNING and INFO rules never change the approval.

            Return ONLY a single JSON object (no markdown, no comments, no code fences) with this shape:

            {
              "approved": boolean,
              "final_severity": "OVERRIDE_APPROVE" | "REJECT" | "WARNING" | "INFO",
              "final_rule_id": string | null,
              "user_message": string,
              "extracted": {
                "date": string | null,           // YYYY-MM-DD or null if unreadable
                "amountInclTax": number | null,  // grand total including tax
                "issuerCompanyName": string | null,
                "issuerAddress": string | null,
                "expenseType": "food_drink" | "it_equipment" | "transportation" | "other"
              },
              "rules": [
                {
                  "id": string,                  // must match one of the rule ids from the catalog
                  "severity": "OVERRIDE_APPROVE" | "REJECT" | "WARNING" | "INFO",
                  "decision": "FAILED" | "PASSED" | "NOT_APPLICABLE",
                  "user_message": string | null  // short explanation when FAILED, otherwise null
                }
              ]
            }

            Requirements:
            - 'user_message' must be 1–2 short sentences that explain the MAIN reason for approval or rejection,
              and what the employee should do next (e.g. upload clearer photo, explain weekend expense, etc.).
            - The 'rules' array must contain one entry for EVERY rule in the catalog, reusing the exact id and severity.
            - When information is missing from the extracted description, set it to null in 'extracted' and use NOT_APPLICABLE
              for rules that depend on that field.
            - Do not add extra top-level fields and do not wrap the JSON in backticks or markdown.

            Validation rule catalog:
            {{RULES_BLOCK}}

            Decision priority:
            - First, consider rules with severity=OVERRIDE_APPROVE in ascending priority.
            - Next, consider rules with severity=REJECT in ascending priority.
            - WARNING and INFO rules never change approval, but their user_message should still help the employee.
            """;

    @Transactional
    void onStart(@Observes StartupEvent ev) {
        boolean changed = false;
        changed |= replaceIfPlaceholder("VISION_EXTRACTION", VISION_EXTRACTION_PROMPT_BODY);
        changed |= replaceIfPlaceholder("POLICY_VALIDATION", POLICY_VALIDATION_PROMPT_BODY);
        if (changed) {
            log.info("[PromptBootstrapper] Seed placeholders replaced with real bodies; reloading AIConfigSnapshot.");
            snapshot.reload();
        }
    }

    private boolean replaceIfPlaceholder(String key, String code) {
        AIPromptTemplate t = AIPromptTemplate.findById(key);
        if (t == null) {
            log.warnf("[PromptBootstrapper] ai_prompt_template row missing for key=%s; nothing to replace.", key);
            return false;
        }
        if (t.body == null || t.body.startsWith("__SEED_")) {
            t.body = code;
            t.updatedAt = LocalDateTime.now();
            t.updatedBy = "SYSTEM_BOOT";
            t.currentVersion = Math.max(1, t.currentVersion);
            log.infof("[PromptBootstrapper] Replaced placeholder body for key=%s", key);
            return true;
        }
        return false;
    }
}
