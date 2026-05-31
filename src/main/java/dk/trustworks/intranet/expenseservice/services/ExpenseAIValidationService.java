package dk.trustworks.intranet.expenseservice.services;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import dk.trustworks.intranet.aggregates.bidata.model.BiDataPerDay;
import dk.trustworks.intranet.aggregates.budgets.model.EmployeeBudgetPerDayAggregate;
import dk.trustworks.intranet.apis.openai.OpenAIService;
import dk.trustworks.intranet.domain.user.entity.User;
import dk.trustworks.intranet.domain.user.entity.UserContactinfo;
import dk.trustworks.intranet.expenseservice.model.Expense;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.transaction.Transactional;
import lombok.extern.jbosslog.JBossLog;

import java.time.LocalDate;
import java.time.format.DateTimeParseException;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

@JBossLog
@ApplicationScoped
public class ExpenseAIValidationService {

    @Inject
    OpenAIService openAIService;

    @Inject
    AIConfigSnapshot config;

    @Inject
    MerchantAllowListService merchantAllowList;

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final Pattern PARAM_PLACEHOLDER = Pattern.compile("\\{\\{([a-z0-9_]+)\\}\\}");

    public record ExtractedExpenseData(LocalDate date, Double amountInclTax, String issuerCompanyName, String issuerAddress, String expenseType) {}
    /**
     * Result of AI validation. Phase 1 adds the 3-outcome tier ({@code outcome}),
     * {@code confidence}, {@code softFlags} (non-blocking finding labels), and an optional
     * pre-set {@code attentionOwner}/{@code attentionKind} (used only for the AMOUNT_MISMATCH
     * block, where routing is not rule-driven). {@code approved}/{@code reason}/{@code ruleIds}
     * are retained for back-compat with {@code validateExpenseReceipt} and the dry-run.
     */
    public record AIResult(boolean approved, String reason, java.util.List<String> ruleIds,
                           String outcome, Double confidence, java.util.List<String> softFlags,
                           String attentionOwner, String attentionKind) {

        public static final String OUTCOME_APPROVE   = "APPROVE";
        public static final String OUTCOME_SOFT_FLAG = "SOFT_FLAG";
        public static final String OUTCOME_BLOCK     = "BLOCK";

        /** Transient/processing error: leave AI fields NULL so the sweep retries. */
        public static AIResult error(String reason) {
            return new AIResult(false, reason, java.util.List.of(), null, null,
                    java.util.List.of(), null, null);
        }
    }

    /**
     * Returns the policy-validation system prompt with the rule catalog substituted in
     * place of the {@code {{RULES_BLOCK}}} placeholder. Rules come from {@link AIConfigSnapshot},
     * filtered to severities that drive the decision (REJECT, OVERRIDE_APPROVE), ordered by
     * priority ascending and stable on ruleId.
     */
    String buildPolicyValidationPrompt() {
        String template = config.getPromptBody("POLICY_VALIDATION");
        if (template == null) template = "";
        Map<String, String> params = config.getParameters();
        String rulesBlock = config.getRulesByPriority().stream()
                .filter(r -> "REJECT".equals(r.severity()) || "OVERRIDE_APPROVE".equals(r.severity()))
                .map(r -> "- %s (%s, prio=%d): %s".formatted(
                        r.ruleId(), r.severity(), r.priority(), substituteParameters(r.description(), params)))
                .collect(Collectors.joining("\n"));
        return template.replace("{{RULES_BLOCK}}", rulesBlock);
    }

    static String substituteParameters(String description, Map<String, String> params) {
        if (description == null || description.indexOf("{{") < 0) return description;
        Matcher m = PARAM_PLACEHOLDER.matcher(description);
        StringBuilder out = new StringBuilder(description.length() + 32);
        while (m.find()) {
            String value = params.get(m.group(1));
            m.appendReplacement(out, value != null
                    ? Matcher.quoteReplacement(value)
                    : Matcher.quoteReplacement(m.group()));
        }
        m.appendTail(out);
        return out.toString();
    }

    /** Returns the vision-extraction system prompt from the snapshot. */
    String buildVisionExtractionPrompt() {
        return config.getPromptBody("VISION_EXTRACTION");
    }


    /**
     * Extracts comprehensive unstructured text description from a RECEIPT IMAGE (base64).
     * Uses vision API without web search to describe everything visible in the receipt.
     * The resulting text will be used for subsequent validation instead of the image itself.
     *
     * @param base64ReceiptImage Base64-encoded receipt image (JPEG format)
     * @return Unstructured text description of the receipt (all visible details)
     */
    public String extractExpenseData(String base64ReceiptImage) {
        try {
            int contentLen = base64ReceiptImage == null ? 0 : base64ReceiptImage.length();
            log.infof("[AI-Extract] Starting comprehensive extraction from image. base64 len=%d", contentLen);
            if (base64ReceiptImage == null || base64ReceiptImage.isEmpty()) {
                log.warn("No image content provided for AI extraction");
                return "Receipt image not provided or empty.";
            }

            // System prompt for comprehensive unstructured extraction (loaded from AIConfigSnapshot)
            String system = buildVisionExtractionPrompt();

            String userInstruction = "Analyze this receipt image and provide a comprehensive description of all visible details.";

            // Use plain text vision method (no web search, no structured output)
            String description = openAIService.askSimpleQuestionWithImage(
                    system,
                    userInstruction,
                    base64ReceiptImage,
                    "image/jpeg"
            );

            String result = description == null ? "" : description.trim();
            String resultPreview = result.length() > 500 ? result.substring(0, 500) + "..." : result;
            log.debugf("[AI-Extract] OpenAI comprehensive description preview=%s", resultPreview);

            // Validate response
            if (result.isEmpty() || result.equals("{}") || result.startsWith("Validation error:")) {
                log.warnf("[AI-Extract] Invalid or empty response: %s", resultPreview);
                return "Error: Unable to extract information from receipt image. " + result;
            }

            log.infof("[AI-Extract] Extraction complete, description length=%d characters", result.length());
            return result;

        } catch (Exception e) {
            log.error("Failed to extract expense data via OpenAI (vision)", e);
            return "Error: Exception during receipt extraction - " + e.getMessage();
        }
    }

    /**
     * Validates an expense using extracted receipt text description instead of image.
     * Uses text-based validation with web search enabled for location/venue verification.
     *
     * @param extractedReceiptText Comprehensive text description of receipt (from extractExpenseData)
     * @param expense Expense record with user-entered details
     * @param user User making the expense claim
     * @param contact User contact information (home address for proximity checks)
     * @param bi Business intelligence data for the expense date
     * @param budgetsForDay Client budgets for the expense date
     * @return AIResult with approval status, reason, and IDs of every REJECT/OVERRIDE_APPROVE rule that fired
     */
    public AIResult validateWithExtractedText(String extractedReceiptText,
                                              Expense expense,
                                              User user,
                                              UserContactinfo contact,
                                              BiDataPerDay bi,
                                              List<EmployeeBudgetPerDayAggregate> budgetsForDay) {
        try {
            int textLen = extractedReceiptText == null ? 0 : extractedReceiptText.length();
            log.infof("[AI-Validate] Start (text-based validation). expenseUuid=%s, useruuid=%s, textLen=%d",
                    expense.getUuid(), expense.getUseruuid(), textLen);

            if (extractedReceiptText == null || extractedReceiptText.isBlank()) {
                log.warn("[AI-Validate] No extracted text provided");
                return AIResult.error("Validation error: No receipt description available");
            }

            LocalDate contextDate = deriveContextDate(expense);
            String officeAddress = "Pustervig 3, 1126 København K";
            String homeAddress = formatHomeAddress(contact);

            String contextText = buildValidationContext(expense, user, contact, bi,
                    budgetsForDay, contextDate, officeAddress, homeAddress);

            // System prompt now carries the full rule catalog (sourced from AIConfigSnapshot);
            // the user prompt only carries the receipt + structured context.
            String systemPrompt = buildPolicyValidationPrompt();

            StringBuilder userPrompt = new StringBuilder();
            userPrompt.append("""
                Validate this expense using the extracted receipt description below, the context
                data, and the validation rule catalog.

                EXTRACTED RECEIPT DESCRIPTION:
                """);
            userPrompt.append(extractedReceiptText);
            userPrompt.append("\n\n");
            userPrompt.append("""
                Use the extracted description to:
                - Understand the receipt date, merchant, address, and total including tax.
                - Judge whether the receipt was readable and complete.
                - Analyze line items (food vs. alcohol, software, number of meals, etc.).
                - Use web search if needed to verify store locations, distances, or venue types.

                """);
            userPrompt.append(contextText)
                    .append("\nNow evaluate all rules and return the JSON result.");

            // Use new text-only method with web search and JSON schema
            String aiResponse = openAIService.askWithSchemaAndWebSearch(
                    systemPrompt,
                    userPrompt.toString(),
                    buildUnifiedValidationJsonSchema(),
                    "ExpenseValidationResult",
                    fallbackJson(),
                    "DK"
            );

            String resp = aiResponse == null ? "" : aiResponse.trim();
            String respPreview = resp.length() > 500 ? resp.substring(0, 500) + "..." : resp;
            log.debugf("[AI-Validate] OpenAI raw response preview=%s", respPreview);

            if (resp.isEmpty() || resp.startsWith("Validation error:")) {
                log.warnf("[AI-Validate] Invalid or empty AI response: %s", respPreview);
                return AIResult.error("AI validation error: invalid OpenAI response");
            }

            JsonNode root = safeParseJson(aiResponse);

            boolean approved = root.path("approved").asBoolean(false);
            String userMessage = root.path("user_message").asText(null);
            if (userMessage == null || userMessage.isBlank()) {
                // Backwards compatible: also accept "reason"
                userMessage = root.path("reason").asText("AI validation error: missing user_message");
            }

            // Parse extracted fields for logging and persistence
            JsonNode extracted = root.path("extracted");
            Double extractedAmount = null;
            String extractedMerchant = null;
            if (extracted.isObject()) {
                log.debugf("[AI-Validate] Extracted -> date=%s, amount=%s, issuer=%s, addr=%s, type=%s",
                        extracted.path("date").isNull() ? "null" : extracted.path("date").asText(),
                        extracted.path("amountInclTax").isNull() ? "null" : extracted.path("amountInclTax").asText(),
                        extracted.path("issuerCompanyName").isNull() ? "null" : extracted.path("issuerCompanyName").asText(),
                        extracted.path("issuerAddress").isNull() ? "null" : extracted.path("issuerAddress").asText(),
                        extracted.path("expenseType").isNull() ? "null" : extracted.path("expenseType").asText());
                JsonNode amountNode = extracted.path("amountInclTax");
                extractedAmount = (!amountNode.isNull() && amountNode.isNumber()) ? amountNode.asDouble() : null;
                JsonNode merchantNode = extracted.path("issuerCompanyName");
                extractedMerchant = (!merchantNode.isNull() && merchantNode.isTextual()) ? merchantNode.asText() : null;
            }
            // Parse guestCount (revives the per-person rule + Impact Preview).
            Integer extractedGuestCount = null;
            if (extracted.isObject()) {
                JsonNode gcNode = extracted.path("guestCount");
                extractedGuestCount = (!gcNode.isNull() && gcNode.isInt()) ? gcNode.asInt() : null;
            }
            persistExtractedFacts(expense.getUuid(), extractedAmount, extractedGuestCount, extractedMerchant);

            // Phase 1: the receipt is audit evidence, not the data source. An unreadable photo no
            // longer hard-blocks; the real signal is extracted != entered amount (AMOUNT_MISMATCH),
            // handled inside normalizePolicyVerdict via the outcome combiner.
            AIResult normalized = normalizePolicyVerdict(
                    root, approved, userMessage, extractedMerchant,
                    extractedAmount, expense.getAmount());
            log.infof("[AI-Validate] Final decision -> outcome=%s, approved=%s, confidence=%s, msg=%s, ruleIds=%s, soft=%s",
                    normalized.outcome(), normalized.approved(), normalized.confidence(),
                    normalized.reason(), normalized.ruleIds(), normalized.softFlags());
            return normalized;

        } catch (Exception e) {
            log.error("Failed to validate expense via OpenAI (text-based validation with web search)", e);
            return AIResult.error("AI validation error: " + e.getMessage());
        }
    }

    private LocalDate deriveContextDate(Expense expense) {
        if (expense == null) return null;
        if (expense.getExpensedate() != null) return expense.getExpensedate();
        return expense.getDatecreated();
    }

    private String formatHomeAddress(UserContactinfo contact) {
        if (contact == null) return null;
        String street = contact.getStreetname() == null ? "" : contact.getStreetname();
        String postal = contact.getPostalcode() == null ? "" : contact.getPostalcode();
        String city   = contact.getCity() == null ? "" : contact.getCity();
        String full   = String.format("%s, %s %s", street, postal, city).trim();
        return full.isBlank() ? null : full;
    }

    private String safeHours(java.math.BigDecimal val) {
        return val == null ? "0" : val.toPlainString();
    }

    private String buildValidationContext(Expense expense,
                                          User user,
                                          UserContactinfo contact,
                                          BiDataPerDay bi,
                                          List<EmployeeBudgetPerDayAggregate> budgetsForDay,
                                          LocalDate contextDate,
                                          String officeAddress,
                                          String homeAddress) {
        StringBuilder ctx = new StringBuilder();

        String dayOfWeek = contextDate != null ? contextDate.getDayOfWeek().name() : "UNKNOWN";
        boolean isWeekend = contextDate != null && contextDate.getDayOfWeek().getValue() >= 6;

        ctx.append("Derived context:\n")
                .append(mapLine("contextDate", contextDate == null ? null : contextDate.toString()))
                .append(mapLine("dayOfWeek", dayOfWeek))
                .append(mapLine("isWeekend", String.valueOf(isWeekend)))
                .append(mapLine("companyOfficeAddress", officeAddress))
                .append(mapLine("homeAddress", homeAddress))
                .append("\n");

        ctx.append("Expense record:\n")
                .append(mapLine("uuid", expense.getUuid()))
                .append(mapLine("useruuid", expense.getUseruuid()))
                .append(mapLine("amountFieldDKK", String.valueOf(expense.getAmount())))
                .append(mapLine("expensedateField", String.valueOf(expense.getExpensedate())))
                .append(mapLine("account", expense.getAccount()))
                .append(mapLine("description", expense.getDescription()))
                .append("\n");

        if (user != null) {
            ctx.append("User:\n")
                    .append(mapLine("uuid", user.getUuid()))
                    .append(mapLine("name", user.getFullname()))
                    .append(mapLine("email", user.getEmail()))
                    .append("\n");
        }

        if (bi != null) {
            ctx.append("BiDataPerDay:\n")
                    .append(mapLine("documentDate", String.valueOf(bi.documentDate)))
                    .append(mapLine("registeredBillableHours",
                            bi.registeredBillableHours != null ? bi.registeredBillableHours.toPlainString() : null))
                    .append(mapLine("netAvailableHours",
                            bi.netAvailableHoursColumn != null ? bi.netAvailableHoursColumn.toPlainString() : null))
                    .append(mapLine("vacationHours", safeHours(bi.vacationHours)))
                    .append(mapLine("sickHours", safeHours(bi.sickHours)))
                    .append(mapLine("paidLeaveHours", safeHours(bi.paidLeaveHours)))
                    .append(mapLine("nonPaydLeaveHours", safeHours(bi.nonPaydLeaveHours)))
                    .append(mapLine("maternityLeaveHours", safeHours(bi.maternityLeaveHours)))
                    .append("\n");
        }

        ctx.append("EmployeeBudgetPerDayAggregates (count=")
                .append(budgetsForDay != null ? budgetsForDay.size() : 0)
                .append("):\n");

        if (budgetsForDay != null && !budgetsForDay.isEmpty()) {
            int i = 0;
            StringBuilder clientNames = new StringBuilder();
            for (EmployeeBudgetPerDayAggregate b : budgetsForDay) {
                if (i < 5) {
                    ctx.append("- client=")
                            .append(b.getClient() != null ? b.getClient().getName() : "null")
                            .append(", contract=")
                            .append(b.getContract() != null ? b.getContract().getUuid() : "null")
                            .append(", hours=").append(b.getBudgetHours())
                            .append(", rate=").append(b.getRate())
                            .append("\n");
                } else if (i == 5) {
                    ctx.append("...\n");
                }
                if (b.getClient() != null) {
                    if (!clientNames.isEmpty()) clientNames.append(", ");
                    clientNames.append(b.getClient().getName());
                }
                i++;
            }
            if (!clientNames.isEmpty()) {
                ctx.append(mapLine("clientNamesForDay", clientNames.toString()));
            }
        }

        return ctx.toString();
    }

    private JsonNode safeParseJson(String raw) {
        if (raw == null) return MAPPER.createObjectNode();

        String trimmed = raw.trim();

        // Quick check: if it doesn't start with '{', it's probably not a single JSON object
        if (!trimmed.startsWith("{")) {
            log.warnf("[AI-Validate] Response does not start with '{': %s",
                    trimmed.length() > 200 ? trimmed.substring(0, 200) + "..." : trimmed);
            return MAPPER.createObjectNode();
        }

        // Try direct parse first
        try {
            return MAPPER.readTree(trimmed);
        } catch (Exception first) {
            log.warn("[AI-Validate] Direct JSON parse failed, attempting salvage", first);

            // Try to salvage: cut to last closing brace
            int lastBrace = trimmed.lastIndexOf('}');
            if (lastBrace > 0) {
                String salvaged = trimmed.substring(0, lastBrace + 1);
                try {
                    return MAPPER.readTree(salvaged);
                } catch (Exception second) {
                    log.error("[AI-Validate] Salvage parse also failed", second);
                }
            }

            // Give up and return empty node
            return MAPPER.createObjectNode();
        }
    }



    private String mapLine(String key, String val) {
        return "- " + key + ": " + (val == null ? "null" : val) + "\n";
    }



    private static ObjectNode buildUnifiedValidationJsonSchema() {
        ObjectNode schema = MAPPER.createObjectNode();
        schema.put("type", "object");
        schema.put("additionalProperties", false);

        ObjectNode props = schema.putObject("properties");

        // approved: boolean
        ObjectNode approved = props.putObject("approved");
        approved.put("type", "boolean");
        approved.put("description", "Final approval decision for the expense.");

        // final_severity: enum
        ObjectNode finalSeverity = props.putObject("final_severity");
        finalSeverity.put("type", "string");
        finalSeverity.put("description",
                "Severity of the rule that determined the final decision.");
        ArrayNode finalSeverityEnum = finalSeverity.putArray("enum");
        finalSeverityEnum.add("OVERRIDE_APPROVE");
        finalSeverityEnum.add("REJECT");
        finalSeverityEnum.add("WARNING");
        finalSeverityEnum.add("INFO");

        // final_rule_id: string | null
        ObjectNode finalRuleId = props.putObject("final_rule_id");
        finalRuleId.put("description",
                "ID of the rule that primarily determined the decision, or null if none.");
        ArrayNode finalRuleAnyOf = finalRuleId.putArray("anyOf");
        finalRuleAnyOf.addObject().put("type", "string");
        finalRuleAnyOf.addObject().put("type", "null");

        // user_message: string
        ObjectNode userMessage = props.putObject("user_message");
        userMessage.put("type", "string");
        userMessage.put("description",
                "Short explanation for the employee (1–2 sentences).");
        userMessage.put("minLength", 5);
        userMessage.put("maxLength", 500);

        // extracted object
        ObjectNode extracted = props.putObject("extracted");
        extracted.put("type", "object");
        extracted.put("additionalProperties", false);
        ObjectNode extractedProps = extracted.putObject("properties");

        // extracted.date: string(YYYY-MM-DD) | null
        ObjectNode date = extractedProps.putObject("date");
        date.put("description", "Receipt date in YYYY-MM-DD format, or null if unreadable.");
        ArrayNode dateAnyOf = date.putArray("anyOf");
        ObjectNode dateString = dateAnyOf.addObject();
        dateString.put("type", "string");
        // Simple YYYY-MM-DD pattern
        dateString.put("pattern", "^\\d{4}-\\d{2}-\\d{2}$");
        dateAnyOf.addObject().put("type", "null");

        // extracted.amountInclTax: number | null
        ObjectNode amountInclTax = extractedProps.putObject("amountInclTax");
        amountInclTax.put("description", "Grand total including tax, or null if unreadable.");
        ArrayNode amountAnyOf = amountInclTax.putArray("anyOf");
        amountAnyOf.addObject().put("type", "number");
        amountAnyOf.addObject().put("type", "null");

        // extracted.issuerCompanyName: string | null
        ObjectNode issuerCompanyName = extractedProps.putObject("issuerCompanyName");
        issuerCompanyName.put("description",
                "Name of the issuing company on the receipt, or null if unreadable.");
        ArrayNode issuerNameAnyOf = issuerCompanyName.putArray("anyOf");
        issuerNameAnyOf.addObject().put("type", "string");
        issuerNameAnyOf.addObject().put("type", "null");

        // extracted.issuerAddress: string | null
        ObjectNode issuerAddress = extractedProps.putObject("issuerAddress");
        issuerAddress.put("description",
                "Address of the issuing company as printed on the receipt, or null.");
        ArrayNode issuerAddrAnyOf = issuerAddress.putArray("anyOf");
        issuerAddrAnyOf.addObject().put("type", "string");
        issuerAddrAnyOf.addObject().put("type", "null");

        // extracted.expenseType: enum (non-null)
        ObjectNode expenseType = extractedProps.putObject("expenseType");
        expenseType.put("type", "string");
        expenseType.put("description",
                "Normalized expense type classification.");
        ArrayNode expenseTypeEnum = expenseType.putArray("enum");
        expenseTypeEnum.add("food_drink");
        expenseTypeEnum.add("it_equipment");
        expenseTypeEnum.add("transportation");
        expenseTypeEnum.add("other");

        // extracted.guestCount: integer | null (revives R_MEAL_COST_PER_PERSON + Impact Preview)
        ObjectNode guestCount = extractedProps.putObject("guestCount");
        guestCount.put("description", "Number of guests/people the receipt covers, or null if not indicated.");
        ArrayNode guestCountAnyOf = guestCount.putArray("anyOf");
        guestCountAnyOf.addObject().put("type", "integer");
        guestCountAnyOf.addObject().put("type", "null");

        ArrayNode extractedRequired = extracted.putArray("required");
        extractedRequired.add("date");
        extractedRequired.add("amountInclTax");
        extractedRequired.add("issuerCompanyName");
        extractedRequired.add("issuerAddress");
        extractedRequired.add("expenseType");
        extractedRequired.add("guestCount");

        // rules: array of rule evaluation objects
        ObjectNode rules = props.putObject("rules");
        rules.put("type", "array");
        rules.put("description",
                "Per-rule evaluation results for all validation rules.");
        rules.put("minItems", 0);

        ObjectNode ruleItem = rules.putObject("items");
        ruleItem.put("type", "object");
        ruleItem.put("additionalProperties", false);
        ObjectNode ruleProps = ruleItem.putObject("properties");

        // rules[*].id: string
        ObjectNode ruleId = ruleProps.putObject("id");
        ruleId.put("type", "string");
        ruleId.put("description",
                "Rule ID from the rule catalog (e.g. R_MEAL_COST_PER_PERSON).");

        // rules[*].severity: enum (same as final_severity)
        ObjectNode ruleSeverity = ruleProps.putObject("severity");
        ruleSeverity.put("type", "string");
        ruleSeverity.put("description", "Severity of this specific rule.");
        ArrayNode ruleSeverityEnum = ruleSeverity.putArray("enum");
        ruleSeverityEnum.add("OVERRIDE_APPROVE");
        ruleSeverityEnum.add("REJECT");
        ruleSeverityEnum.add("WARNING");
        ruleSeverityEnum.add("INFO");

        // rules[*].decision: enum
        ObjectNode ruleDecision = ruleProps.putObject("decision");
        ruleDecision.put("type", "string");
        ruleDecision.put("description",
                "Evaluation outcome for this rule.");
        ArrayNode decisionEnum = ruleDecision.putArray("enum");
        decisionEnum.add("FAILED");
        decisionEnum.add("PASSED");
        decisionEnum.add("NOT_APPLICABLE");

        // rules[*].user_message: string | null
        ObjectNode ruleUserMessage = ruleProps.putObject("user_message");
        ruleUserMessage.put("description",
                "Short explanation when the rule FAILED, null otherwise.");
        ArrayNode ruleUserMsgAnyOf = ruleUserMessage.putArray("anyOf");
        ruleUserMsgAnyOf.addObject().put("type", "string");
        ruleUserMsgAnyOf.addObject().put("type", "null");

        // rules[*].confidence: number 0..1 (drives the BLOCK/SOFT_FLAG tier in the combiner)
        ObjectNode ruleConfidence = ruleProps.putObject("confidence");
        ruleConfidence.put("type", "number");
        ruleConfidence.put("description",
                "Model confidence (0.0-1.0) that this rule's decision is correct.");
        ruleConfidence.put("minimum", 0);
        ruleConfidence.put("maximum", 1);

        ArrayNode ruleRequired = ruleItem.putArray("required");
        ruleRequired.add("id");
        ruleRequired.add("severity");
        ruleRequired.add("decision");
        ruleRequired.add("user_message");
        ruleRequired.add("confidence");

        // top-level required fields
        ArrayNode required = schema.putArray("required");
        required.add("approved");
        required.add("final_severity");
        required.add("final_rule_id");
        required.add("user_message");
        required.add("extracted");
        required.add("rules");

        return schema;
    }

    /**
     * Returns true if the given merchant matches any active allow-list entry
     * for the given rule. Used by the merchant allow-list bypass — when a
     * R_MERCHANT_ALLOW_* rule's verdict identifies a known-allowed merchant,
     * the verdict is downgraded to "approved" before being persisted.
     *
     * @param ruleId   the rule ID being evaluated (typically a R_MERCHANT_ALLOW_* rule)
     * @param merchant the AI-extracted merchant name from the receipt
     * @return true if the merchant is allow-listed for this rule
     */
    public boolean isAllowListed(String ruleId, String merchant) {
        return merchantAllowList.matches(ruleId, merchant);
    }

    AIResult normalizePolicyVerdict(JsonNode root,
                                    boolean approved,
                                    String userMessage,
                                    String extractedMerchant,
                                    Double extractedAmount,
                                    Double enteredAmount) {
        java.util.Set<String> suppressedRuleIds = new java.util.LinkedHashSet<>();
        java.util.List<ExpenseAIOutcomeCombiner.FiredRule> fired = new java.util.ArrayList<>();
        java.util.Set<String> seen = new java.util.LinkedHashSet<>();

        JsonNode rulesNode = root.path("rules");
        if (rulesNode.isArray()) {
            for (JsonNode r : rulesNode) {
                String id       = r.path("id").asText(null);
                String severity = r.path("severity").asText("");
                String decision = r.path("decision").asText("");
                double confidence = r.path("confidence").isNumber() ? r.path("confidence").asDouble() : 1.0;
                if (id == null || !"FAILED".equals(decision)) continue;
                if (!("REJECT".equals(severity) || "OVERRIDE_APPROVE".equals(severity))) continue;
                if (isSuppressedAllowListRule(id, extractedMerchant)) {
                    suppressedRuleIds.add(id);
                    log.debugf("[AI-Validate] Suppressing allow-listed merchant verdict for rule=%s merchant=%s",
                            id, extractedMerchant);
                    continue;
                }
                if (!seen.add(id)) continue;
                AIConfigSnapshot.RuleView view = config.getRule(id);
                String mode = view != null && view.outcomeMode() != null
                        ? view.outcomeMode() : "BLOCK";
                double threshold = view != null && view.confidenceThreshold() != null
                        ? view.confidenceThreshold() : 0.0;
                // OVERRIDE_APPROVE rules approve the expense — never a blocking finding.
                if ("OVERRIDE_APPROVE".equals(severity)) {
                    log.infof("[AI-Validate] OVERRIDE_APPROVE rule fired: %s — approving.", id);
                    return new AIResult(true,
                            "Approved by override rule " + id, java.util.List.of(),
                            AIResult.OUTCOME_APPROVE, null, java.util.List.of(), null, null);
                }
                fired.add(new ExpenseAIOutcomeCombiner.FiredRule(id, confidence, mode, threshold));
            }
        }

        if (!approved) {
            JsonNode explicitRuleIds = root.path("ruleIds");
            if (explicitRuleIds.isArray()) {
                for (JsonNode n : explicitRuleIds) {
                    String id = textOrNull(n);
                    if (addFallbackRuleUnlessSuppressed(id, extractedMerchant,
                            suppressedRuleIds, seen, fired)) {
                        return new AIResult(true,
                                "Approved by override rule " + id, java.util.List.of(),
                                AIResult.OUTCOME_APPROVE, null, java.util.List.of(), null, null);
                    }
                }
            }

            String finalRuleId = textOrNull(root.path("final_rule_id"));
            if (addFallbackRuleUnlessSuppressed(finalRuleId, extractedMerchant,
                    suppressedRuleIds, seen, fired)) {
                return new AIResult(true,
                        "Approved by override rule " + finalRuleId, java.util.List.of(),
                        AIResult.OUTCOME_APPROVE, null, java.util.List.of(), null, null);
            }
        }

        // Merchant allow-list: if only allow-listed rules fired, approve (preserves prior behavior).
        if (fired.isEmpty() && !suppressedRuleIds.isEmpty()) {
            log.infof("[AI-Validate] Approving — only allow-listed merchant rules fired: %s", suppressedRuleIds);
            return new AIResult(true,
                    "Approved because the merchant is on the allow-list for the fired policy rule.",
                    java.util.List.of(), AIResult.OUTCOME_APPROVE, null, java.util.List.of(), null, null);
        }

        double softPct = config.getDecimalParameter("amount_mismatch_soft_pct",
                new java.math.BigDecimal("0.15")).doubleValue();
        double blockPct = config.getDecimalParameter("amount_mismatch_block_pct",
                new java.math.BigDecimal("0.40")).doubleValue();
        ExpenseAIOutcomeCombiner.AmountSignal amountSignal =
                ExpenseAIOutcomeCombiner.classifyAmount(extractedAmount, enteredAmount, softPct, blockPct);

        ExpenseAIOutcomeCombiner.Outcome o = ExpenseAIOutcomeCombiner.combine(fired, amountSignal);

        boolean isApproved = !AIResult.OUTCOME_BLOCK.equals(o.outcome());
        if (!approved && AIResult.OUTCOME_APPROVE.equals(o.outcome())) {
            o = new ExpenseAIOutcomeCombiner.Outcome(AIResult.OUTCOME_BLOCK, null,
                    java.util.List.of(), java.util.List.of(), null, null);
            isApproved = false;
        }
        String reason = userMessage != null && !userMessage.isBlank()
                ? userMessage
                : (AIResult.OUTCOME_BLOCK.equals(o.outcome())
                    ? "This expense needs attention before it can be processed."
                    : "Approved.");
        return new AIResult(isApproved, reason, o.blockingRuleIds(),
                o.outcome(), o.confidence(), o.softFlags(), o.attentionOwner(), o.attentionKind());
    }

    private boolean isSuppressedAllowListRule(String id, String extractedMerchant) {
        return id != null
                && id.startsWith("R_MERCHANT_ALLOW_")
                && isAllowListed(id, extractedMerchant);
    }

    private boolean addFallbackRuleUnlessSuppressed(String id,
                                                    String extractedMerchant,
                                                    java.util.Set<String> suppressedRuleIds,
                                                    java.util.Set<String> seen,
                                                    java.util.List<ExpenseAIOutcomeCombiner.FiredRule> fired) {
        if (id == null || id.isBlank()) return false;
        if (isSuppressedAllowListRule(id, extractedMerchant)) {
            suppressedRuleIds.add(id);
            log.debugf("[AI-Validate] Suppressing allow-listed merchant verdict for rule=%s merchant=%s",
                    id, extractedMerchant);
            return false;
        }
        if (!seen.add(id)) return false;

        AIConfigSnapshot.RuleView view = config.getRule(id);
        if (view != null && "OVERRIDE_APPROVE".equals(view.severity())) {
            log.infof("[AI-Validate] OVERRIDE_APPROVE fallback rule fired: %s — approving.", id);
            return true;
        }
        String mode = view != null && view.outcomeMode() != null
                ? view.outcomeMode() : "BLOCK";
        double threshold = view != null && view.confidenceThreshold() != null
                ? view.confidenceThreshold() : 0.0;
        fired.add(new ExpenseAIOutcomeCombiner.FiredRule(id, 1.0, mode, threshold));
        return false;
    }

    private static String textOrNull(JsonNode node) {
        if (node == null || node.isNull() || node.isMissingNode()) return null;
        String value = node.asText(null);
        return value == null || value.isBlank() ? null : value;
    }

    /**
     * Persists AI-extracted receipt facts onto the expense row.
     *
     * <p>Called automatically from {@link #validateWithExtractedText} after each successful
     * AI validation run. The three base columns are written; the fourth column
     * ({@code extracted_per_person_dkk}) is a STORED generated column and is therefore
     * read-only in JPA — the DB recomputes it whenever the base columns change.
     *
     * <p>Extracted-field availability notes:
     * <ul>
     *   <li>{@code amountDkk} — populated from {@code extracted.amountInclTax} in the
     *       unified validation JSON schema response.
     *   <li>{@code guestCount} — AI vision response does not currently expose a structured
     *       guest-count field; callers pass {@code null} until the prompt/schema is extended.
     *       Impact Preview gracefully degrades for the per-person cap rule when this is null.
     *   <li>{@code merchant} — populated from {@code extracted.issuerCompanyName} in the
     *       unified validation JSON schema response.
     * </ul>
     *
     * @param expenseUuid UUID of the expense to update
     * @param amountDkk   AI-extracted receipt total in DKK, or {@code null} if unavailable
     * @param guestCount  AI-extracted guest count, or {@code null} if unavailable
     * @param merchant    AI-extracted merchant name, or {@code null} if unavailable
     */
    @Transactional
    public void persistExtractedFacts(String expenseUuid, Double amountDkk, Integer guestCount, String merchant) {
        Expense e = Expense.findById(expenseUuid);
        if (e == null) {
            log.warnf("[AI-Persist] Expense not found for extracted-facts update: %s", expenseUuid);
            return;
        }
        e.setExtractedAmountDkk(amountDkk);
        e.setExtractedGuestCount(guestCount);
        e.setExtractedMerchantName(merchant);
        e.persist();
        log.debugf("[AI-Persist] Persisted extracted facts for expense=%s amount=%s guestCount=%s merchant=%s",
                expenseUuid, amountDkk, guestCount, merchant);
    }

    /**
     * Fallback JSON that strictly matches the unified validation schema.
     * Used when the model refuses or the OpenAI call fails.
     */
    private static String fallbackJson() {
        return """
        {
          "approved": false,
          "final_severity": "REJECT",
          "final_rule_id": "R_FALLBACK",
          "user_message": "AI validation failed. Please have this expense reviewed manually.",
          "extracted": {
            "date": null,
            "amountInclTax": null,
            "issuerCompanyName": null,
            "issuerAddress": null,
            "expenseType": "other",
            "guestCount": null
          },
          "rules": [
            {
              "id": "R_FALLBACK",
              "severity": "REJECT",
              "decision": "FAILED",
              "user_message": "AI validation failed internally; this expense must be reviewed manually.",
              "confidence": 1.0
            }
          ]
        }
        """;
    }


}
