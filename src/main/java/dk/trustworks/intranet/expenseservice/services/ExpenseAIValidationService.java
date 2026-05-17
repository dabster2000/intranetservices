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

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final Pattern PARAM_PLACEHOLDER = Pattern.compile("\\{\\{([a-z0-9_]+)\\}\\}");

    public record ExtractedExpenseData(LocalDate date, Double amountInclTax, String issuerCompanyName, String issuerAddress, String expenseType) {}
    public record ValidationDecision(boolean approved, String reason) {}
    /**
     * Richer result returned by {@link #validateWithExtractedText} so the
     * consumer can route AI-rejected expenses into the correct review state
     * based on which rule(s) fired. {@link ValidationDecision} is preserved for
     * legacy callers (e.g. {@code validateWithContext}, {@code ExpenseService.validateExpenseReceipt}).
     */
    public record AIResult(boolean approved, String reason, java.util.List<String> ruleIds) {}

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
            log.infof("[AI-Extract] OpenAI comprehensive description preview=%s", resultPreview);

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

    public ValidationDecision validateWithContext(Expense expense,
                                                  ExtractedExpenseData extracted,
                                                  User user,
                                                  UserContactinfo contact,
                                                  BiDataPerDay bi,
                                                  List<EmployeeBudgetPerDayAggregate> budgetsForDay) {
        try {
            // Derive the most relevant date to evaluate
            LocalDate contextDate = extracted.date() != null
                    ? extracted.date()
                    : (expense.getExpensedate() != null ? expense.getExpensedate() : expense.getDatecreated());
            String dayOfWeek = contextDate != null ? contextDate.getDayOfWeek().name() : "UNKNOWN";
            boolean isWeekend = (contextDate != null) && (contextDate.getDayOfWeek().getValue() >= 6);

            // Summarize leave/absence signals from BI data (null-safe)
            String vacationHours = (bi != null && bi.vacationHours != null) ? bi.vacationHours.toPlainString() : "0";
            String sickHours = (bi != null && bi.sickHours != null) ? bi.sickHours.toPlainString() : "0";
            String paidLeaveHours = (bi != null && bi.paidLeaveHours != null) ? bi.paidLeaveHours.toPlainString() : "0";
            String nonPaydLeaveHours = (bi != null && bi.nonPaydLeaveHours != null) ? bi.nonPaydLeaveHours.toPlainString() : "0";
            String maternityLeaveHours = (bi != null && bi.maternityLeaveHours != null) ? bi.maternityLeaveHours.toPlainString() : "0";

            String officeAddress = "Pustervig 3, 1126 København K";
            String homeAddress = (contact != null)
                    ? String.format("%s, %s %s",
                    contact.getStreetname() == null ? "" : contact.getStreetname(),
                    contact.getPostalcode() == null ? "" : contact.getPostalcode(),
                    contact.getCity() == null ? "" : contact.getCity())
                    : null;

            log.infof("[AI-Validate] Start. expenseUuid=%s, useruuid=%s, user=%s",
                    expense.getUuid(), expense.getUseruuid(), user != null ? user.getFullname() : "null");
            log.infof("[AI-Validate] Extracted -> date=%s, amount=%s, issuer=%s, issuerAddressPresent=%s, type=%s",
                    String.valueOf(extracted.date()),
                    String.valueOf(extracted.amountInclTax()),
                    extracted.issuerCompanyName(),
                    extracted.issuerAddress() != null && !extracted.issuerAddress().isEmpty(),
                    extracted.expenseType());
            log.infof("[AI-Validate] Derived -> contextDate=%s, dayOfWeek=%s, isWeekend=%s, hasBI=%s, budgetsCount=%d, homeAddressPresent=%s",
                    String.valueOf(contextDate),
                    dayOfWeek,
                    String.valueOf(isWeekend),
                    String.valueOf(bi != null),
                    budgetsForDay != null ? budgetsForDay.size() : 0,
                    String.valueOf(homeAddress != null && !homeAddress.isBlank()));

            StringBuilder ctx = new StringBuilder();
            ctx.append("You are validating a submitted employee expense for compliance with company policy.\n")
                    .append("Use the data to decide approve vs reject and give one concise reason.\n\n")
                    .append("Extracted from attachment:\n")
                    .append(mapLine("date", String.valueOf(extracted.date())))
                    .append(mapLine("amountInclTaxDKK", extracted.amountInclTax() == null ? null : String.valueOf(extracted.amountInclTax())))
                    .append(mapLine("issuerCompanyName", extracted.issuerCompanyName()))
                    .append(mapLine("issuerAddress", extracted.issuerAddress()))
                    .append(mapLine("expenseType", extracted.expenseType()))
                    .append("\n")
                    .append("Expense record:\n")
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
            if (contact != null) {
                ctx.append("UserContactinfo:\n")
                        .append(mapLine("street", contact.getStreetname()))
                        .append(mapLine("postalcode", contact.getPostalcode()))
                        .append(mapLine("city", contact.getCity()))
                        .append(mapLine("homeAddress", homeAddress))
                        .append("\n");
            }
            if (bi != null) {
                ctx.append("BiDataPerDay:\n")
                        .append(mapLine("documentDate", String.valueOf(bi.documentDate)))
                        .append(mapLine("registeredBillableHours", bi.registeredBillableHours!=null?bi.registeredBillableHours.toPlainString():null))
                        .append(mapLine("netAvailableHours", bi.netAvailableHoursColumn!=null?bi.netAvailableHoursColumn.toPlainString():null))
                        .append(mapLine("vacationHours", vacationHours))
                        .append(mapLine("sickHours", sickHours))
                        .append(mapLine("paidLeaveHours", paidLeaveHours))
                        .append(mapLine("nonPaydLeaveHours", nonPaydLeaveHours))
                        .append(mapLine("maternityLeaveHours", maternityLeaveHours))
                        .append("\n");
            }
            ctx.append("EmployeeBudgetPerDayAggregates (count=").append(budgetsForDay != null ? budgetsForDay.size() : 0).append("):\n");
            if (budgetsForDay != null) {
                int i = 0;
                StringBuilder clientNames = new StringBuilder();
                for (EmployeeBudgetPerDayAggregate b : budgetsForDay) {
                    if (i < 5) {
                        ctx.append("- client=").append(b.getClient() != null ? b.getClient().getName() : "null")
                                .append(", contract=").append(b.getContract() != null ? b.getContract().getUuid() : "null")
                                .append(", hours=").append(b.getBudgetHours()).append(", rate=").append(b.getRate()).append("\n");
                    } else if (i == 5) {
                        ctx.append("...\n");
                    }
                    if (b.getClient()!=null) {
                        if (!clientNames.isEmpty()) clientNames.append(", ");
                        clientNames.append(b.getClient().getName());
                    }
                    i++;
                }
                if (!clientNames.isEmpty()) {
                    ctx.append(mapLine("clientNamesForDay", clientNames.toString()));
                }
            }
            ctx.append("Derived context:\n")
                    .append(mapLine("contextDate", String.valueOf(contextDate)))
                    .append(mapLine("dayOfWeek", dayOfWeek))
                    .append(mapLine("isWeekend", String.valueOf(isWeekend)))
                    .append(mapLine("companyOfficeAddress", officeAddress))
                    .append("\n");

            // Validation policy – explicit rules for LLM enforcement
            ctx.append("Validation rules (DKK):\n")
                    .append("1) Receipt Readability: Verify that the receipt image is readable and clear. Check that text is legible, not blurry, and key information (amount, date, merchant name) is visible. If receipt is unreadable, blurry, or missing critical information, REJECT with reason 'Receipt image unreadable - please upload clear photo'.\n")
                    .append("2) Office Food/Drink: If expenseType='food_drink' and purchase is within 1 km of the office (Pustervig 3, 1126 København K), REJECT with reason 'Food/drink expenses not reimbursable {give explanation}'.\n")
                    .append("3) Meal Cost Threshold: If expenseType='food_drink', divide amountInclTax by number of people. If cost per person > 125 DKK, REJECT with reason 'Meal cost exceeds 125 DKK per person limit'.\n")
                    .append("4) Transportation: If expenseType='transportation', verify: (1) Client budgets exist (EmployeeBudgetPerDayAggregates count > 0), (2) Trip within work hours 8-17. If any rule fails, REJECT with reason 'Transportation not eligible - {explanation}'.\n")
                    .append("5) Weekend expenses for food and drink: Check if contextDate is weekend (dayOfWeek=SATURDAY/SUNDAY or isWeekend=true). If weekend and food or drink, REJECT with reason 'Weekend food and drink expenses not reimbursable'.\n")
                    .append("6) Leave/Absence Conflict: Check if any leave hours > 0 (vacationHours, sickHours, paidLeaveHours, nonPaydLeaveHours, maternityLeaveHours). If yes AND expenseType='food_drink' or 'transportation', REJECT with reason 'Expenses {define type} during leave/absence not reimbursable'.\n")
                    .append("7) IT Equipment: If expenseType='it_equipment' and amountInclTax > 500 DKK, REJECT with reason 'IT equipment over 500 DKK requires pre-approval - contact admin'.\n")
                    .append("8) Date Mismatch: Calculate absolute difference between extracted receipt date and expensedate field. If difference > 30 days, REJECT with reason 'Receipt date differs by more than 30 days - explanation required'.\n")
                    .append("9) Entertainment Venues: Use web search to verify if issuerCompanyName is entertainment venue (bar, nightclub, casino). If yes, REJECT with reason 'Entertainment venue expenses not reimbursable'.\n")
                    .append("11) Multi-Person Meals: If expenseType='food_drink', analyze receipt items/description for multiple people indicators (multiple entrees, 'for 2', 'group dinner'). If for multiple people, REJECT with reason 'Food/drink for multiple people not reimbursable - individual meals only'.\n")
                    .append("12) Software Licenses: Check if purchase is software license/subscription (keywords: 'software', 'license', 'subscription', 'SaaS', vendors like Adobe, Microsoft, OpenAI, Anthropic). If software license, REJECT with reason 'Software purchases must go through IT procurement - contact IT department'.\n")
                    .append("13) Home Proximity: Check if purchase location (issuerAddress) is near employee's home address using web search to verify distances. If within ~1km of home, REJECT with reason 'Purchase near home address - private expense'.\n")
                    .append("\n")
                    .append("The following rules override the above and means the receipt is approved and reimbursed:\n")
                    .append("1) Food and drinks on the following addresses are always reimbursed: Nyropsgade or Landgreven\n")
                    .append("\n")
                    .append("Decide approve vs reject and give ONE concise concise but meaningful reason so the user can understand the decision.\n");

            // System prompt with JSON schema description (plain text format, not json_schema)
            // GPT-5 models don't support json_schema with tools (web_search), so we use plain text + manual validation
            String system = """
                You are validating a submitted employee expense for compliance with company policy.
                Return ONLY valid JSON matching this exact schema (no markdown, no code fences):
                {
                  "approved": boolean,
                  "reason": "string (5-200 characters, one concise sentence)"
                }

                Use the data to decide approve vs reject and give one concise but meaningful reason so the user can understand the decision.
                Keep 'reason' one concise but meaningful sentence.
                """;

            String aiResponse = openAIService.askQuestionWithWebSearchPlainText(system, ctx.toString(), "DK");
            String resp = aiResponse == null ? "{}" : aiResponse.trim();
            String respPreview = resp.length() > 500 ? resp.substring(0, 500) + "..." : resp;
            log.infof("[AI-Validate] OpenAI raw response preview=%s", respPreview);

            // Parse and validate JSON structure manually
            JsonNode node = MAPPER.readTree(resp);

            // Validate required fields are present
            if (!node.has("approved") || !node.has("reason")) {
                log.warnf("[AI-Validate] Invalid response structure (missing required fields): %s", respPreview);
                return new ValidationDecision(false, "AI validation error: Invalid response format");
            }

            boolean approved = node.path("approved").asBoolean(false);
            String reasonStr = node.path("reason").asText("No reason provided");
            log.infof("[AI-Validate] Decision -> approved=%s, reason=%s", String.valueOf(approved), reasonStr);
            return new ValidationDecision(approved, reasonStr);

        } catch (Exception e) {
            log.error("Failed to validate expense via OpenAI", e);
            return new ValidationDecision(false, "AI validation error: " + e.getMessage());
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
                return new AIResult(false, "Validation error: No receipt description available", java.util.List.of());
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
            log.infof("[AI-Validate] OpenAI raw response preview=%s", respPreview);

            if (resp.isEmpty() || resp.startsWith("Validation error:")) {
                log.warnf("[AI-Validate] Invalid or empty AI response: %s", respPreview);
                return new AIResult(false, "AI validation error: invalid OpenAI response", java.util.List.of());
            }

            JsonNode root = safeParseJson(aiResponse);

            boolean approved = root.path("approved").asBoolean(false);
            String userMessage = root.path("user_message").asText(null);
            if (userMessage == null || userMessage.isBlank()) {
                // Backwards compatible: also accept "reason"
                userMessage = root.path("reason").asText("AI validation error: missing user_message");
            }

            // Optional: log extracted fields for debugging
            JsonNode extracted = root.path("extracted");
            if (extracted.isObject()) {
                log.infof("[AI-Validate] Extracted -> date=%s, amount=%s, issuer=%s, addr=%s, type=%s",
                        extracted.path("date").isNull() ? "null" : extracted.path("date").asText(),
                        extracted.path("amountInclTax").isNull() ? "null" : extracted.path("amountInclTax").asText(),
                        extracted.path("issuerCompanyName").isNull() ? "null" : extracted.path("issuerCompanyName").asText(),
                        extracted.path("issuerAddress").isNull() ? "null" : extracted.path("issuerAddress").asText(),
                        extracted.path("expenseType").isNull() ? "null" : extracted.path("expenseType").asText());
            }

            // Collect rule IDs that fired (REJECT or OVERRIDE_APPROVE with decision=FAILED).
            // These drive the routing decision in the consumer. We accept either:
            //   - top-level "ruleIds": ["R_FOO", ...] (explicit field)
            //   - "rules": [{id, severity, decision}] (existing schema — derive failed REJECT/OVERRIDE_APPROVE)
            java.util.List<String> firedRuleIds = new java.util.ArrayList<>();
            JsonNode explicitRuleIds = root.path("ruleIds");
            if (explicitRuleIds.isArray()) {
                explicitRuleIds.forEach(n -> {
                    if (n != null && !n.isNull()) firedRuleIds.add(n.asText());
                });
            }
            JsonNode rulesNode = root.path("rules");
            if (rulesNode.isArray()) {
                for (JsonNode r : rulesNode) {
                    String id       = r.path("id").asText(null);
                    String severity = r.path("severity").asText("");
                    String decision = r.path("decision").asText("");
                    log.infof("[AI-Validate] Rule %s severity=%s decision=%s msg=%s",
                            id,
                            severity,
                            decision,
                            r.path("user_message").asText());
                    if (id != null
                            && "FAILED".equals(decision)
                            && ("REJECT".equals(severity) || "OVERRIDE_APPROVE".equals(severity))
                            && !firedRuleIds.contains(id)) {
                        firedRuleIds.add(id);
                    }
                }
            }
            // Defensive: include final_rule_id if it isn't already in the list.
            String finalRuleId = root.path("final_rule_id").isNull()
                    ? null
                    : root.path("final_rule_id").asText(null);
            if (finalRuleId != null && !finalRuleId.isBlank() && !firedRuleIds.contains(finalRuleId)) {
                firedRuleIds.add(finalRuleId);
            }

            log.infof("[AI-Validate] Final decision -> approved=%s, userMessage=%s, firedRuleIds=%s",
                    approved, userMessage, firedRuleIds);
            return new AIResult(approved, userMessage, java.util.List.copyOf(firedRuleIds));

        } catch (Exception e) {
            log.error("Failed to validate expense via OpenAI (text-based validation with web search)", e);
            return new AIResult(false, "AI validation error: " + e.getMessage(), java.util.List.of());
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

        ArrayNode extractedRequired = extracted.putArray("required");
        extractedRequired.add("date");
        extractedRequired.add("amountInclTax");
        extractedRequired.add("issuerCompanyName");
        extractedRequired.add("issuerAddress");
        extractedRequired.add("expenseType");

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

        ArrayNode ruleRequired = ruleItem.putArray("required");
        ruleRequired.add("id");
        ruleRequired.add("severity");
        ruleRequired.add("decision");
        ruleRequired.add("user_message");

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
            "expenseType": "other"
          },
          "rules": [
            {
              "id": "R_FALLBACK",
              "severity": "REJECT",
              "decision": "FAILED",
              "user_message": "AI validation failed internally; this expense must be reviewed manually."
            }
          ]
        }
        """;
    }


}
