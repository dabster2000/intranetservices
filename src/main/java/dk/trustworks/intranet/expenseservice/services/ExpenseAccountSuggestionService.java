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
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * W3: GL-account suggestions for the "Assign account" micro-queue — the employee's
 * most-used accounts, restricted to active accounts of their current company. The
 * 9998 fallback ("Other – not sure") is never suggested: it is the reason the row
 * is in the queue in the first place.
 *
 * <p>{@link #assignablePlanFor} exposes the same restriction as the whole picker list,
 * so Finance can choose an account without looking the number up in e-conomic.
 */
@ApplicationScoped
public class ExpenseAccountSuggestionService {

    /** The classifier's Finance-review fallback account — never a valid assignment. */
    public static final int FALLBACK_ACCOUNT = 9998;

    public record Suggestion(int account, String accountName, int timesUsed) {}

    /**
     * One pickable account. Field names mirror the {@code /expenses/categories} DTO the
     * employee-facing expense form already consumes, so the frontend reuses one picker
     * component and one type for both flows.
     */
    public record PlanAccount(int accountNumber, String accountName, boolean defaultAccount) {}

    /** One category of {@link PlanAccount}s. {@code defaultCategory} holds the most-used account. */
    public record PlanCategory(String categoryName, boolean defaultCategory,
                               List<PlanAccount> expenseAccounts) {}

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
     * The employee's whole assignable account plan, grouped by category — what the
     * "Assign account" picker lists. Same restriction as {@link #requireAssignable}
     * (active accounts of the employee's current company, minus the 9998 fallback),
     * narrowed further to active categories so Finance sees exactly the plan the
     * employee submits against. Never a superset of what the assign call accepts.
     *
     * <p>The employee's own most-used account is flagged {@code defaultAccount} (and its
     * category {@code defaultCategory}) so the picker can pre-select it.
     */
    public List<PlanCategory> assignablePlanFor(String useruuid) {
        Company company = companyOf(useruuid);
        if (company == null) return List.of();

        // Projection, not a `join fetch` — no collection fetch, no in-memory pagination.
        List<Object[]> rows = em.createQuery(
                        "select c.categoryName, a.accountNumber, a.accountName " +
                        "from ExpenseCategory c join c.expenseAccounts a " +
                        "where c.active = true and a.active = true " +
                        "  and a.companyuuid = :companyuuid and a.accountNumber <> :fallback " +
                        "order by c.categoryName, a.accountNumber", Object[].class)
                .setParameter("companyuuid", company.getUuid())
                .setParameter("fallback", FALLBACK_ACCOUNT)
                .getResultList();

        List<Suggestion> top = suggestFor(useruuid, 1);
        int mostUsed = top.isEmpty() ? Integer.MIN_VALUE : top.get(0).account();

        return groupByCategory(rows, mostUsed);
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

    /**
     * Pure grouping of {@code [categoryName, accountNumber, accountName]} rows into
     * categories, preserving the query's ordering and flagging {@code mostUsed}.
     * Package-private static for plain unit tests.
     */
    static List<PlanCategory> groupByCategory(List<Object[]> rows, int mostUsed) {
        Map<String, List<PlanAccount>> byCategory = new LinkedHashMap<>();
        for (Object[] row : rows) {
            int accountNumber = ((Number) row[1]).intValue();
            byCategory.computeIfAbsent((String) row[0], k -> new ArrayList<>())
                    .add(new PlanAccount(accountNumber, (String) row[2], accountNumber == mostUsed));
        }
        return byCategory.entrySet().stream()
                .map(e -> new PlanCategory(
                        e.getKey(),
                        e.getValue().stream().anyMatch(PlanAccount::defaultAccount),
                        List.copyOf(e.getValue())))
                .toList();
    }
}
