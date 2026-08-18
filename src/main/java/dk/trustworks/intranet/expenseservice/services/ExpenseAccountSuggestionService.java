package dk.trustworks.intranet.expenseservice.services;

import dk.trustworks.intranet.aggregates.users.services.UserService;
import dk.trustworks.intranet.domain.user.entity.User;
import dk.trustworks.intranet.expenseservice.model.ExpenseAccount;
import dk.trustworks.intranet.model.Company;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.persistence.EntityManager;
import jakarta.persistence.Query;
import jakarta.ws.rs.BadRequestException;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * W3: GL-account suggestions for the "Assign account" micro-queue — the employee's
 * most-used accounts, restricted to active accounts of their current company. The
 * 9998 fallback ("Other – not sure") is never suggested: it is the reason the row
 * is in the queue in the first place.
 */
@ApplicationScoped
public class ExpenseAccountSuggestionService {

    /** The classifier's Finance-review fallback account — never a valid assignment. */
    public static final int FALLBACK_ACCOUNT = 9998;

    public record Suggestion(int account, String accountName, int timesUsed) {}

    @Inject UserService userService;
    @Inject EntityManager em;

    /** Top-{@code limit} accounts the employee has actually used before. */
    public List<Suggestion> suggestFor(String useruuid, int limit) {
        Map<Integer, String> assignable = assignableAccounts(useruuid);
        if (assignable.isEmpty()) return List.of();
        Query q = em.createNativeQuery(
            "SELECT account, COUNT(*) AS uses FROM expenses " +
            "WHERE useruuid = :u AND account IS NOT NULL AND account <> '' " +
            "  AND status <> 'DELETED' " +
            "GROUP BY account ORDER BY uses DESC");
        q.setParameter("u", useruuid);
        @SuppressWarnings("unchecked")
        List<Object[]> rows = q.getResultList();
        return topSuggestions(rows, assignable, limit);
    }

    /**
     * Validate that {@code account} is an active account of the employee's current
     * company (and not the 9998 fallback). Returns the account row for its name.
     */
    public ExpenseAccount requireAssignable(String useruuid, int account) {
        if (account == FALLBACK_ACCOUNT) {
            throw new BadRequestException("account 9998 is the Finance-review fallback — pick a real account");
        }
        Company company = companyOf(useruuid);
        ExpenseAccount row = company == null ? null : ExpenseAccount.<ExpenseAccount>find(
                "companyuuid = ?1 and accountNumber = ?2 and active = true",
                company.getUuid(), account).firstResult();
        if (row == null) {
            throw new BadRequestException(
                "account " + account + " is not an active expense account for the employee's company");
        }
        return row;
    }

    /** Active account number → name for the employee's current company, minus the fallback. */
    private Map<Integer, String> assignableAccounts(String useruuid) {
        Company company = companyOf(useruuid);
        if (company == null) return Map.of();
        List<ExpenseAccount> active = ExpenseAccount.list(
                "companyuuid = ?1 and active = true", company.getUuid());
        return active.stream()
                .filter(a -> a.getAccountNumber() != FALLBACK_ACCOUNT)
                .collect(Collectors.toMap(ExpenseAccount::getAccountNumber, ExpenseAccount::getAccountName,
                        (a, b) -> a));
    }

    private Company companyOf(String useruuid) {
        if (useruuid == null) return null;
        User user = userService.findById(useruuid, false);
        if (user == null) return null;
        var status = userService.getUserStatus(user, LocalDate.now());
        return status == null ? null : status.getCompany();
    }

    /**
     * Pure top-N selection: keep frequency order, drop accounts that are not
     * assignable (inactive, other company, unparsable, or the fallback).
     * Package-private static for plain unit tests.
     */
    static List<Suggestion> topSuggestions(List<Object[]> frequencyRows,
                                           Map<Integer, String> assignable, int limit) {
        List<Suggestion> out = new ArrayList<>();
        for (Object[] row : frequencyRows) {
            if (out.size() >= limit) break;
            int account;
            try {
                account = Integer.parseInt((String) row[0]);
            } catch (NumberFormatException e) {
                continue;
            }
            String name = assignable.get(account);
            if (name == null) continue;
            out.add(new Suggestion(account, name, ((Number) row[1]).intValue()));
        }
        return List.copyOf(out);
    }
}
