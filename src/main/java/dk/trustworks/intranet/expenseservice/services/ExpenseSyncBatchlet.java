package dk.trustworks.intranet.expenseservice.services;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import dk.trustworks.intranet.expenseservice.model.Expense;
import dk.trustworks.intranet.expenseservice.remote.EconomicsAPI;
import jakarta.batch.api.AbstractBatchlet;
import jakarta.enterprise.context.Dependent;
import jakarta.enterprise.context.control.ActivateRequestContext;
import jakarta.inject.Inject;
import jakarta.inject.Named;
import jakarta.ws.rs.core.Response;
import lombok.extern.jbosslog.JBossLog;

import java.util.List;

@JBossLog
@Named("expenseSyncBatchlet")
@Dependent
public class ExpenseSyncBatchlet extends AbstractBatchlet {

    @Inject
    EconomicsService economicsService;

    @Inject
    ExpenseService expenseService;

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private String truncate(String s, int max) {
        if (s == null) return null;
        if (s.length() <= max) return s;
        return s.substring(0, Math.max(0, max)) + "...";
    }

    @Override
    @ActivateRequestContext
    public String process() throws Exception {
        try {
            // Fetch expenses that have a voucher triple present
            List<Expense> expenses = Expense.find("vouchernumber > 0 and journalnumber is not null and accountingyear is not null").list();
            log.info("ExpenseSyncBatchlet processing expenses: " + expenses.size());
            for (Expense expense : expenses) {
                syncExpense(expense);
            }
            return "COMPLETED";
        } catch (Exception e) {
            log.error("ExpenseSyncBatchlet failed", e);
            throw e;
        }
    }

    private void syncExpense(Expense expense) {
        try {
            EconomicsAPI api = economicsService.getApiForExpense(expense);
            int jn = expense.getJournalnumber();
            String year = expense.getAccountingyear();
            int vn = expense.getVouchernumber();

            log.info("Expense sync start: uuid=" + expense.getUuid() + ", status=" + expense.getStatus() + ", account=" + expense.getAccount() + ", triple=" + jn + "/" + year + "-" + vn);

            // 1) Look in journal entries (unbooked)
            String journalFilter = "voucher.voucherNumber$eq:" + vn;
            Response jr = api.getJournalEntries(jn, journalFilter, 1000);
            int jrStatus = jr != null ? jr.getStatus() : -1;
            String jrBody = null;
            try {
                if (jr != null) jrBody = jr.readEntity(String.class);
            } finally {
                if (jr != null) jr.close();
            }
            log.info("Expense " + expense.getUuid() + ": journal query jn=" + jn + ", filter='" + journalFilter + "', status=" + jrStatus);
            log.debug("Journal response body (truncated): " + truncate(jrBody, 800));

            boolean inJournal = jrStatus >= 200 && jrStatus < 300 && hasAnyEntries(jrBody);
            if (inJournal) {
                // Unbooked; reflect current account if changed
                String accountFromEntries = extractFirstAccount(jrBody);
                if (accountFromEntries != null && !accountFromEntries.equals(expense.getAccount())) {
                    log.info("Expense " + expense.getUuid() + ": account differs (journal); updating " + expense.getAccount() + " -> " + accountFromEntries);
                    expense.setAccount(accountFromEntries);
                } else {
                    log.debug("Expense " + expense.getUuid() + ": account unchanged based on journal");
                }
                expenseService.updateStatus(expense, ExpenseService.STATUS_PROCESSING);
                log.info("Expense " + expense.getUuid() + " marked PROCESSING (unbooked in journal)");
                return;
            }

            // 2) If not in journal, check booked entries under accounting-years
            String yearFilter = "voucherNumber$eq:" + vn;
            Response yr = api.getYearEntries(year, yearFilter, 1000);
            int yrStatus = yr != null ? yr.getStatus() : -1;
            String yrBody = null;
            try {
                if (yr != null) yrBody = yr.readEntity(String.class);
            } finally {
                if (yr != null) yr.close();
            }
            log.info("Expense " + expense.getUuid() + ": year query year=" + year + ", filter='" + yearFilter + "', status=" + yrStatus);
            log.debug("Year response body (truncated): " + truncate(yrBody, 800));

            boolean booked = yrStatus >= 200 && yrStatus < 300 && hasAnyEntries(yrBody);
            if (booked) {
                // Booked; update account and mark processed
                String accountFromEntries = extractFirstAccount(yrBody);
                if (accountFromEntries != null && !accountFromEntries.equals(expense.getAccount())) {
                    log.info("Expense " + expense.getUuid() + ": account differs (booked); updating " + expense.getAccount() + " -> " + accountFromEntries);
                    expense.setAccount(accountFromEntries);
                }
                expenseService.updateStatus(expense, ExpenseService.STATUS_PROCESSED);
                log.info("Expense " + expense.getUuid() + " marked PROCESSED (booked under accounting-years)");
                return;
            }

            // 3) Not found anywhere -> consider deleted
            log.warn("Expense " + expense.getUuid() + ": voucher not found in journal or accounting-years; marking DELETED");
            expenseService.updateStatus(expense, ExpenseService.STATUS_DELETED);
        } catch (Exception ex) {
            log.error("Sync failed for expense " + expense.getUuid() + ": " + ex.getMessage(), ex);
        }
    }

    private boolean hasAnyEntries(String body) {
        if (body == null || body.isEmpty()) return false;
        try {
            JsonNode root = MAPPER.readTree(body);
            if (root == null) return false;
            if (root.isArray()) return root.size() > 0;
            if (root.has("collection") && root.get("collection").isArray()) return root.get("collection").size() > 0;
            if (root.has("items") && root.get("items").isArray()) return root.get("items").size() > 0;
            return false;
        } catch (Exception e) {
            return false;
        }
    }

    private String extractFirstAccount(String body) {
        if (body == null || body.isEmpty()) return null;
        try {
            JsonNode root = MAPPER.readTree(body);
            JsonNode first = null;
            if (root.isArray()) {
                first = root.size() > 0 ? root.get(0) : null;
            } else if (root.has("collection") && root.get("collection").isArray()) {
                JsonNode arr = root.get("collection");
                first = arr.size() > 0 ? arr.get(0) : null;
            } else if (root.has("items") && root.get("items").isArray()) {
                JsonNode arr = root.get("items");
                first = arr.size() > 0 ? arr.get(0) : null;
            }
            if (first == null) return null;
            // account.accountNumber may be nested
            JsonNode account = first.get("account");
            if (account != null && account.get("accountNumber") != null) {
                return account.get("accountNumber").asText();
            }
            // Alternative nested structure: entry.account.accountNumber
            if (first.has("entry") && first.get("entry").has("account") && first.get("entry").get("account").has("accountNumber")) {
                return first.get("entry").get("account").get("accountNumber").asText();
            }
            return null;
        } catch (Exception e) {
            return null;
        }
    }
}
