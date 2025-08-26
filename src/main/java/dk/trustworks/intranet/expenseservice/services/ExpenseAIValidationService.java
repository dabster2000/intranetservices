package dk.trustworks.intranet.expenseservice.services;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import dk.trustworks.intranet.aggregates.bidata.model.BiDataPerDay;
import dk.trustworks.intranet.aggregates.budgets.model.EmployeeBudgetPerDayAggregate;
import dk.trustworks.intranet.apis.openai.OpenAIService;
import dk.trustworks.intranet.expenseservice.model.Expense;
import dk.trustworks.intranet.userservice.model.User;
import dk.trustworks.intranet.userservice.model.UserContactinfo;
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

            // Build strict JSON Schema for extraction (only expenseType required)
            ObjectNode schema = MAPPER.createObjectNode();
            schema.put("type", "object");
            ObjectNode props = schema.putObject("properties");

            ObjectNode dateNode = props.putObject("date");
            dateNode.putArray("type").add("string").add("null");
            dateNode.put("pattern", "^\\d{4}-\\d{2}-\\d{2}$");

            props.putObject("amountInclTax")
                    .putArray("type").add("number").add("null");

            props.putObject("issuerCompanyName")
                    .putArray("type").add("string").add("null");

            props.putObject("issuerAddress")
                    .putArray("type").add("string").add("null");

            // FIX spelling -> it_equipment
            props.putObject("expenseType")
                    .put("type", "string")
                    .putArray("enum").add("food_drink").add("it_equipment").add("transportation").add("other");

            schema.putArray("required").add("expenseType");
            schema.put("additionalProperties", false);

            // Short, vision-focused instruction
            String system = "You extract receipt fields and MUST return only JSON matching the schema.";
            String instruction = """
                From the receipt image, extract:
                - date (YYYY-MM-DD)
                - total amount including tax (grand total)
                - issuer company name
                - issuer address (as written on the receipt, no web lookups)
                - expenseType: one of [food_drink, it_equipment, transportation, other]
                If a field is genuinely unknown or unreadable, return null (except expenseType, default to "other").
                """;

            // If the model refuses, we still need schema-valid JSON; fall back to minimal valid object
            String refusalFallback = "{\"date\":null,\"amountInclTax\":null,\"issuerCompanyName\":null,\"issuerAddress\":null,\"expenseType\":\"other\"}";

            // NOTE: mimeType can be auto-detected upstream; using "image/jpeg" as default here
            String json = openAIService.askWithSchemaAndImage(
                    system,
                    instruction,
                    base64ReceiptImage,
                    "image/jpeg",
                    schema,
                    "ExtractedExpenseData",
                    refusalFallback
            );

            String resp = json == null ? "" : json.trim();
            String respPreview = resp.length() > 500 ? resp.substring(0, 500) + "..." : resp;
            log.infof("[AI-Extract] OpenAI raw response preview=%s", respPreview);

            JsonNode node = MAPPER.readTree(resp);
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

            // Validation policy – keep concise to avoid dilution
            ctx.append("Validation rules (DKK):\n")
                    .append("1) Purchases near home likely private unless clear work reason.\n")
                    .append("2) food_drink near office can be office catering; otherwise require meeting/client context.\n")
                    .append("3) food_drink > 125 per person → likely reject unless clearly special/justified.\n")
                    .append("4) Transportation to/from home not reimbursable unless clearly work-related.\n")
                    .append("5) Weekend/holiday → require explicit work reason or reject.\n")
                    .append("6) Sick/vacation/leave → likely reject for food_drink/transport.\n")
                    .append("7) it_equipment > 500 requires pre-approval stated.\n")
                    .append("8) Date mismatch > 3 days without reason → reject.\n")
                    .append("9) Entertainment-only venues w/o client context → reject.\n")
                    .append("10) No client budgets & no BI activity & no justification → reject.\n")
                    .append("Decide approve vs reject and give ONE concise reason.\n");

            // Strict schema for validation: reason must be present and non-trivial
            ObjectNode validateSchema = MAPPER.createObjectNode();
            validateSchema.put("type", "object");
            ObjectNode vProps = validateSchema.putObject("properties");
            vProps.putObject("approved").put("type", "boolean");
            ObjectNode reason = vProps.putObject("reason");
            reason.put("type", "string");
            reason.put("minLength", 5);
            validateSchema.putArray("required").add("approved").add("reason");
            validateSchema.put("additionalProperties", false);

            String system = "Return ONLY JSON matching the schema. Keep 'reason' one concise sentence.";
            String refusalFallback = "{\"approved\": false, \"reason\": \"Model refusal or incomplete output.\"}";

            String aiResponse = openAIService.askQuestionWithSchema(system, ctx.toString(), validateSchema, "ValidationDecision", refusalFallback);
            String resp = aiResponse == null ? "{}" : aiResponse.trim();
            String respPreview = resp.length() > 500 ? resp.substring(0, 500) + "..." : resp;
            log.infof("[AI-Validate] OpenAI raw response preview=%s", respPreview);

            JsonNode node = MAPPER.readTree(resp);
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
