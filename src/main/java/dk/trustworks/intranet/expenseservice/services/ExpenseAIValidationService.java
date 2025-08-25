package dk.trustworks.intranet.expenseservice.services;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
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

    public ExtractedExpenseData extractExpenseData(String attachmentContent) {
        try {
            int contentLen = attachmentContent == null ? 0 : attachmentContent.length();
            log.infof("[AI-Extract] Starting extraction. attachmentContent len=%d", contentLen);
            if (attachmentContent == null || attachmentContent.isEmpty()) {
                log.warn("No attachment content provided for AI extraction");
                return new ExtractedExpenseData(null, null, null, null, "other");
            }
            String snippet = attachmentContent.length() > 12000 ? attachmentContent.substring(0, 12000) : attachmentContent;
            log.infof("[AI-Extract] Built snippet. snippetLen=%d (max 12000)", snippet.length());
            String prompt = "You are an assistant that extracts expense data from raw expense attachment content.\n" +
                    "The content can be OCR text or base64 of a PDF/image.\n" +
                    "Task: Identify the expense document date, the total amount including tax (grand total), the issuer company name, the issuer address, and classify the expense type.\n" +
                    "Rules:\n" +
                    "- expenseType must be ONE of: food_drink, it_equiment, transportation, other.\n" +
                    "- issuerAddress: Prefer the address found in the attachment. If missing, look it up on the internet and return the most likely official address for the issuer company as a single line string. If still unknown, set to null.\n" +
                    "Respond ONLY in strict JSON with keys: date (YYYY-MM-DD), amountInclTax (number with dot), issuerCompanyName (string), issuerAddress (string or null), expenseType (string).\n" +
                    "If unknown, set value to null (except expenseType which must still be one of the four values; use 'other' if unsure). No extra text.\n\n" +
                    "Content:\n" + snippet + "\n\n" +
                    "Output JSON example: {\"date\":\"2025-02-28\",\"amountInclTax\":123.45,\"issuerCompanyName\":\"Acme A/S\",\"issuerAddress\":\"Some Street 12, 2100 Copenhagen\",\"expenseType\":\"food_drink\"}";

            log.infof("[AI-Extract] Sending prompt to OpenAI (chars=%d)", prompt.length());
            String response = openAIService.askQuestion(prompt);
            String resp = response == null ? "" : response.trim();
            if (resp.startsWith("```")) {
                resp = resp.replace("```json", "").replace("```", "").trim();
            }
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
            java.util.List<String> allowed = java.util.List.of("food_drink", "it_equiment", "transportation", "other");
            if (expenseType == null || !allowed.contains(expenseType)) {
                expenseType = "other";
            }
            log.infof("[AI-Extract] Parsed fields -> date=%s, amount=%s, issuerCompanyName=%s, issuerAddress=%s, expenseType=%s",
                    String.valueOf(date), String.valueOf(amount), String.valueOf(issuerCompanyName), String.valueOf(issuerAddress), String.valueOf(expenseType));
            return new ExtractedExpenseData(date, amount, issuerCompanyName, issuerAddress, expenseType);
        } catch (Exception e) {
            log.error("Failed to extract expense data via OpenAI", e);
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
            boolean isWeekend = false;
            if (contextDate != null) {
                switch (contextDate.getDayOfWeek()) {
                    case SATURDAY, SUNDAY -> isWeekend = true;
                    default -> isWeekend = false;
                }
            }

            // Summarize leave/absence signals from BI data (null-safe)
            String vacationHours = (bi != null && bi.vacationHours != null) ? bi.vacationHours.toPlainString() : "0";
            String sickHours = (bi != null && bi.sickHours != null) ? bi.sickHours.toPlainString() : "0";
            String paidLeaveHours = (bi != null && bi.paidLeaveHours != null) ? bi.paidLeaveHours.toPlainString() : "0";
            String nonPaydLeaveHours = (bi != null && bi.nonPaydLeaveHours != null) ? bi.nonPaydLeaveHours.toPlainString() : "0";
            String maternityLeaveHours = (bi != null && bi.maternityLeaveHours != null) ? bi.maternityLeaveHours.toPlainString() : "0";

            // Build context prompt
            String officeAddress = "Pustervig 3, 1126 København K";
            String homeAddress = (contact != null)
                    ? String.format("%s, %s %s", 
                        contact.getStreetname() == null ? "" : contact.getStreetname(),
                        contact.getPostalcode() == null ? "" : contact.getPostalcode(),
                        contact.getCity() == null ? "" : contact.getCity())
                    : null;

            // High-level info about this validation
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
               .append("Return STRICT JSON only: {\"approved\": true|false, \"reason\": \"single concise sentence referencing key checks\"}. No extra text, no markdown.\n\n")
               .append("Data provided:\n");

            // Attachment extracted
            ctx.append("Extracted from attachment:\n")
               .append(mapLine("date", String.valueOf(extracted.date())))
               .append(mapLine("amountInclTaxDKK", extracted.amountInclTax() == null ? null : String.valueOf(extracted.amountInclTax())))
               .append(mapLine("issuerCompanyName", extracted.issuerCompanyName()))
               .append(mapLine("issuerAddress", extracted.issuerAddress()))
               .append(mapLine("expenseType", extracted.expenseType()))
               .append("\n");

            // Expense fields
            ctx.append("Expense record:\n")
               .append(mapLine("uuid", expense.getUuid()))
               .append(mapLine("useruuid", expense.getUseruuid()))
               .append(mapLine("amountFieldDKK", String.valueOf(expense.getAmount())))
               .append(mapLine("expensedateField", String.valueOf(expense.getExpensedate())))
               .append(mapLine("account", expense.getAccount()))
               .append(mapLine("description", expense.getDescription()))
               .append("\n");

            // User
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

            // BI data
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

            // Budget data
            ctx.append("EmployeeBudgetPerDayAggregates (count=").append(budgetsForDay != null ? budgetsForDay.size() : 0).append("):\n");
            if (budgetsForDay != null) {
                int i = 0;
                StringBuilder clientNames = new StringBuilder();
                for (EmployeeBudgetPerDayAggregate b : budgetsForDay) {
                    if (i < 5) {
                        ctx.append("- client=").append(b.getClient() != null ? b.getClient().getName() : "null").append(", contract=").append(b.getContract() != null ? b.getContract().getUuid() : "null").append(", hours=").append(b.getBudgetHours()).append(", rate=").append(b.getRate()).append("\n");
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

            // Derived context
            ctx.append("Derived context:\n")
               .append(mapLine("contextDate", String.valueOf(contextDate)))
               .append(mapLine("dayOfWeek", dayOfWeek))
               .append(mapLine("isWeekend", String.valueOf(isWeekend)))
               .append(mapLine("companyOfficeAddress", officeAddress))
               .append("\n");

            // Validation checks and policy
            ctx.append("Validation policy and checks (assume amounts in DKK):\n")
               .append("- Define 'close' as roughly within 1km walking distance unless otherwise clear from context.\n")
               .append("- Use issuerAddress and company names along with the user's homeAddress and officeAddress to estimate proximity.\n")
               .append("- If description or receipt text indicates number of persons, use it; otherwise default to 1 person.\n")
               .append("- Treat Denmark public holidays as non-working days if contextDate is one of them (you may infer from general knowledge).\n")
               .append("Checks to apply:\n")
               .append("1) Is the purchase location close to the employee's home? Flag as potentially private unless a clear work reason exists.\n")
               .append("2) If expenseType=food_drink and location is close to officeAddress, this can be valid for office catering; otherwise require meeting/client context.\n")
               .append("3) For food_drink, check if amountInclTax exceeds 125 per person; if so, likely reject unless special occasion is clearly stated.\n")
               .append("4) Transportation that appears to or from the employee's home is typically not reimbursable unless clearly work-related (late client event, travel assignment).\n")
               .append("5) If contextDate is weekend or Danish public holiday, require a clear work reason (client meeting/travel) or reject.\n")
               .append("6) If the user is sick, on vacation, or on leave that day (based on BI hours), expenses like food_drink/transportation are likely invalid.\n")
               .append("Additional checks:\n")
               .append("7) Large IT equipment (expenseType=it_equiment) purchases over 500 DKK usually require pre-approval in description; if missing, reject.\n")
               .append("8) Date mismatch: if extracted date differs from expense record date by more than 3 days without explanation, reject.\n")
               .append("9) If issuerCompanyName is clearly unrelated to business needs (e.g., entertainment-only bar) and no client context is present, reject.\n")
               .append("10) If there is no client budget or hours that day (budgetsForDay empty and BI shows no activity) and expense lacks justification, lean towards reject.\n")
               .append("Decision: Approve only if the checks indicate a plausible, compliant business expense. Otherwise reject.\n\n")
               .append("Output strictly JSON: {\"approved\": true|false, \"reason\": \"short justification referencing the failed/passed checks\"}.\n");

            String prompt = ctx.toString();
            log.infof("[AI-Validate] Sending prompt to OpenAI (chars=%d)", prompt.length());
            String aiResponse = openAIService.askQuestion(prompt);
            String resp = aiResponse == null ? "{}" : aiResponse.trim();
            if (resp.startsWith("```")) {
                resp = resp.replace("```json", "").replace("```", "").trim();
            }
            String respPreview = resp.length() > 500 ? resp.substring(0, 500) + "..." : resp;
            log.infof("[AI-Validate] OpenAI raw response preview=%s", respPreview);
            JsonNode node = MAPPER.readTree(resp);
            boolean approved = node.path("approved").asBoolean(false);
            String reason = node.path("reason").asText("No reason provided");
            log.infof("[AI-Validate] Decision -> approved=%s, reason=%s", String.valueOf(approved), reason);
            return new ValidationDecision(approved, reason);
        } catch (Exception e) {
            log.error("Failed to validate expense via OpenAI", e);
            return new ValidationDecision(false, "AI validation error: " + e.getMessage());
        }
    }

    private String mapLine(String key, String val) {
        return "- " + key + ": " + (val==null?"null":val) + "\n";
    }
}
