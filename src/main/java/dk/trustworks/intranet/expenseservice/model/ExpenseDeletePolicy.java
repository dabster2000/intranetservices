package dk.trustworks.intranet.expenseservice.model;

import java.util.Set;

public final class ExpenseDeletePolicy {

    private static final Set<String> PRE_ECONOMIC_STATUSES = Set.of(
            "CREATED",
            "VALIDATED",
            "UP_FAILED",
            "NO_FILE",
            "NO_USER"
    );

    private ExpenseDeletePolicy() {}

    public static boolean canDelete(Expense expense) {
        return blockedReason(expense) == null;
    }

    public static String blockedReason(Expense expense) {
        if (expense == null) {
            return "Expense not found.";
        }
        if ("DELETED".equals(expense.getStatus())) {
            return "Expense is already deleted.";
        }
        if (hasVoucherReference(expense)) {
            return "Expense already has e-conomic voucher references.";
        }
        if (!PRE_ECONOMIC_STATUSES.contains(expense.getStatus())) {
            return "Expense has already entered the e-conomic upload flow.";
        }
        return null;
    }

    private static boolean hasVoucherReference(Expense expense) {
        return expense.getVouchernumber() > 0
                || expense.getJournalnumber() != null
                || expense.getAccountingyear() != null;
    }
}
