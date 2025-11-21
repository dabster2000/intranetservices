package dk.trustworks.intranet.expenseservice.services;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import dk.trustworks.intranet.aggregates.bidata.model.BiDataPerDay;
import dk.trustworks.intranet.aggregates.budgets.model.EmployeeBudgetPerDayAggregate;
import dk.trustworks.intranet.apis.openai.OpenAIService;
import dk.trustworks.intranet.expenseservice.model.Expense;
import dk.trustworks.intranet.domain.user.entity.User;
import dk.trustworks.intranet.domain.user.entity.UserContactinfo;
import dk.trustworks.intranet.expenseservice.services.rules.RuleSeverity;
import dk.trustworks.intranet.expenseservice.services.rules.ValidationRuleDefinition;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import lombok.extern.jbosslog.JBossLog;

import java.time.LocalDate;
import java.time.format.DateTimeParseException;
import java.util.List;

@JBossLog
@ApplicationScoped
public class ExpenseAIValidationService {

    @Inject
    OpenAIService openAIService;

    private static final ObjectMapper MAPPER = new ObjectMapper();

    public record ExtractedExpenseData(LocalDate date, Double amountInclTax, String issuerCompanyName, String issuerAddress, String expenseType) {}
    public record ValidationDecision(boolean approved, String reason) {}

    private static final List<ValidationRuleDefinition> VALIDATION_RULES = List.of(

            // --- OVERRIDE (always approve) ---
            new ValidationRuleDefinition(
                    "R_OVERRIDE_WHITELIST_ADDRESS",
                    "Food & drink at whitelisted addresses are always reimbursed",
                    """
                    If the receipt clearly shows a venue located on Nyropsgade or Landgreven
                    in Copenhagen, mark this rule as FAILED (condition met). This OVERRIDE
                    means the expense must be approved even if other rules fail.
                    """,
                    RuleSeverity.OVERRIDE_APPROVE,
                    10
            ),

            // --- Hard rejections ---
            new ValidationRuleDefinition(
                    "R_RECEIPT_READABLE",
                    "Receipt image must be readable",
                    """
                    If the receipt image is so blurry, cropped, dark, or low resolution that
                    you cannot confidently read at least the date, total amount including tax,
                    and merchant name, mark this rule as FAILED.
                    """,
                    RuleSeverity.REJECT,
                    10
            ),
            new ValidationRuleDefinition(
                    "R_OFFICE_FOOD_DRINK",
                    "Food/drink near the office is not reimbursable",
                    """
                    If the expense is primarily food or drink and the merchant/address appears
                    to be within roughly 1 km of the office address
                    "Pustervig 3, 1126 København K" (use web_search if helpful),
                    mark this rule as FAILED.
                    """,
                    RuleSeverity.REJECT,
                    20
            ),
            new ValidationRuleDefinition(
                    "R_MEAL_COST_PER_PERSON",
                    "Meal cost per person must be <= 125 DKK",
                    """
                    If the expense is food or drink, estimate the number of people from the
                    receipt and/or description (e.g. line items, 'for 2', etc.). If the
                    total including tax divided by number of people is clearly above
                    125 DKK per person, mark this rule as FAILED.
                    """,
                    RuleSeverity.REJECT,
                    30
            ),
            new ValidationRuleDefinition(
                    "R_TRANSPORTATION_ELIGIBLE",
                    "Transportation requires client budget and work hours",
                    """
                    If the expense is transportation (taxi, train, etc.), mark this rule as
                    FAILED when there is no client budget for the day (0 budgetsForDay) OR
                    when the trip clearly happens outside normal work hours (approx. 08–17)
                    based on context and description.
                    """,
                    RuleSeverity.REJECT,
                    40
            ),
            new ValidationRuleDefinition(
                    "R_WEEKEND_FOOD_DRINK",
                    "Weekend food/drink is not reimbursable",
                    """
                    If the expense is food or drink and the expense date (contextDate) falls
                    on Saturday or Sunday, mark this rule as FAILED.
                    """,
                    RuleSeverity.REJECT,
                    50
            ),
            new ValidationRuleDefinition(
                    "R_LEAVE_CONFLICT",
                    "Expenses during leave are not reimbursable",
                    """
                    If any leave/absence hours (vacation, sick, paid leave, unpaid leave,
                    maternity leave) are > 0 on contextDate AND the expense is food/drink
                    or transportation, mark this rule as FAILED.
                    """,
                    RuleSeverity.REJECT,
                    60
            ),
            new ValidationRuleDefinition(
                    "R_IT_EQUIPMENT_LIMIT",
                    "IT equipment over 500 DKK requires pre‑approval",
                    """
                    If the expense is IT equipment and the total including tax is clearly
                    above 500 DKK, mark this rule as FAILED and explain that pre‑approval
                    from admin is required.
                    """,
                    RuleSeverity.REJECT,
                    70
            ),
            new ValidationRuleDefinition(
                    "R_DATE_MISMATCH",
                    "Receipt date must match expensedate within 30 days",
                    """
                    Compare the receipt date from the image with the expensedate field in the
                    expense record. If they differ by more than 30 days in absolute value,
                    mark this rule as FAILED.
                    """,
                    RuleSeverity.REJECT,
                    80
            ),
            new ValidationRuleDefinition(
                    "R_ENTERTAINMENT_VENUE",
                    "Entertainment venues are not reimbursable",
                    """
                    Use merchant name and (if needed) web_search to check if the venue is a
                    bar, nightclub, casino, or similar entertainment venue. If so, mark this
                    rule as FAILED.
                    """,
                    RuleSeverity.REJECT,
                    90
            ),
            new ValidationRuleDefinition(
                    "R_MULTI_PERSON_MEAL",
                    "Multi‑person meals are not reimbursable",
                    """
                    If the receipt clearly shows that the food/drink is for multiple people
                    (multiple full meals, wording like 'for 2', 'group dinner', etc.) and
                    company policy is 'individual meals only', mark this rule as FAILED.
                    """,
                    RuleSeverity.REJECT,
                    100
            ),
            new ValidationRuleDefinition(
                    "R_SOFTWARE_LICENSE",
                    "Software subscriptions must go via IT procurement",
                    """
                    If the purchase is clearly a software license or subscription (keywords
                    such as 'license', 'subscription', 'SaaS', or typical vendors like Adobe,
                    Microsoft, OpenAI, Anthropic, etc.), mark this rule as FAILED and explain
                    that software purchases must go via IT procurement.
                    """,
                    RuleSeverity.REJECT,
                    110
            ),
            new ValidationRuleDefinition(
                    "R_HOME_PROXIMITY",
                    "Purchases near home are treated as private",
                    """
                    If the merchant/address appears to be within roughly 1 km of the
                    employee's home address (use web_search if helpful), and there is no clear
                    work‑related justification, mark this rule as FAILED.
                    """,
                    RuleSeverity.REJECT,
                    120
            )

            // You can add WARNING/INFO rules later if needed
    );


    /**
     * Extracts fields from a RECEIPT IMAGE (base64). Uses vision + Structured Outputs.
     * No internet lookups are attempted.
     */
    public ExtractedExpenseData extractExpenseData(String base64ReceiptImage) {
        try {
            int contentLen = base64ReceiptImage == null ? 0 : base64ReceiptImage.length();
            log.infof("[AI-Extract] Starting extraction from image. base64 len=%d", contentLen);
            if (base64ReceiptImage == null || base64ReceiptImage.isEmpty()) {
                log.warn("No image content provided for AI extraction");
                return new ExtractedExpenseData(null, null, null, null, "other");
            }

            // System prompt with embedded JSON schema (plain text format, not json_schema)
            // GPT-5 models don't support json_schema format, so we embed schema in prompt and parse manually
            String system = """
                You are a receipt data extraction assistant for expense reimbursement.
                Return ONLY valid JSON matching this exact schema (no markdown, no code fences):
                {
                  "date": "string (YYYY-MM-DD format) or null",
                  "amountInclTax": number or null,
                  "issuerCompanyName": "string or null",
                  "issuerAddress": "string or null",
                  "expenseType": "one of [food_drink, it_equipment, transportation, other]"
                }

                From the receipt image, extract:
                - date (YYYY-MM-DD format)
                - total amount including tax (grand total)
                - issuer company name
                - issuer address (as written on the receipt, no web lookups)
                - expenseType: one of [food_drink, it_equipment, transportation, other]

                If a field is genuinely unknown or unreadable, return null (except expenseType, default to "other").
                Return ONLY the JSON object, no explanations.
                """;

            // Use plain text vision method (works with GPT-5, unlike json_schema)
            String json = openAIService.askSimpleQuestionWithImage(
                    system,
                    "", // No separate instruction - all in system prompt
                    base64ReceiptImage,
                    "image/jpeg"
            );

            String resp = json == null ? "" : json.trim();
            String respPreview = resp.length() > 500 ? resp.substring(0, 500) + "..." : resp;
            log.infof("[AI-Extract] OpenAI raw response preview=%s", respPreview);

            // Validate response structure before parsing
            if (resp.isEmpty() || resp.equals("{}") || resp.startsWith("Validation error:")) {
                log.warnf("[AI-Extract] Invalid or empty response: %s", respPreview);
                // Return minimal valid object
                resp = "{\"date\":null,\"amountInclTax\":null,\"issuerCompanyName\":null,\"issuerAddress\":null,\"expenseType\":\"other\"}";
            }

            JsonNode node = MAPPER.readTree(resp);

            // Validate required field exists
            if (!node.has("expenseType")) {
                log.warnf("[AI-Extract] Missing required field 'expenseType': %s", respPreview);
                // Return minimal valid object
                return new ExtractedExpenseData(null, null, null, null, "other");
            }
            LocalDate date = null;
            Double amount = null;

            if (node.hasNonNull("date")) {
                try {
                    date = LocalDate.parse(node.get("date").asText());
                } catch (DateTimeParseException e) {
                    log.warn("AI returned unparsable date: " + node.get("date").asText());
                }
            }
            if (node.has("amountInclTax") && !node.get("amountInclTax").isNull()) {
                amount = node.get("amountInclTax").asDouble();
            }
            String issuerCompanyName = node.hasNonNull("issuerCompanyName") ? node.get("issuerCompanyName").asText() : null;
            String issuerAddress = (node.has("issuerAddress") && !node.get("issuerAddress").isNull()) ? node.get("issuerAddress").asText() : null;

            String expenseType = node.hasNonNull("expenseType") ? node.get("expenseType").asText() : "other";
            if (expenseType != null) expenseType = expenseType.trim().toLowerCase();
            List<String> allowed = List.of("food_drink", "it_equipment", "transportation", "other");
            if (expenseType == null || !allowed.contains(expenseType)) {
                expenseType = "other";
            }

            log.infof("[AI-Extract] Parsed fields -> date=%s, amount=%s, issuerCompanyName=%s, issuerAddress=%s, expenseType=%s",
                    String.valueOf(date), String.valueOf(amount), String.valueOf(issuerCompanyName), String.valueOf(issuerAddress), String.valueOf(expenseType));

            return new ExtractedExpenseData(date, amount, issuerCompanyName, issuerAddress, expenseType);

        } catch (Exception e) {
            log.error("Failed to extract expense data via OpenAI (vision)", e);
            return new ExtractedExpenseData(null, null, null, null, "other");
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
                        .append(mapLine("actualUtilization", bi.actualUtilization!=null?bi.actualUtilization.toPlainString():null))
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

    private String mapLine(String key, String val) {
        return "- " + key + ": " + (val==null?"null":val) + "\n";
    }
}

