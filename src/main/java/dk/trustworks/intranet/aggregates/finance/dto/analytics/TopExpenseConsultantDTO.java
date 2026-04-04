package dk.trustworks.intranet.aggregates.finance.dto.analytics;

/**
 * Top expense consultant data point.
 * Represents a consultant ranked by total expense amount in the trailing 12 months.
 */
public record TopExpenseConsultantDTO(
        /** Consultant user UUID. */
        String useruuid,
        /** First name. */
        String firstname,
        /** Last name. */
        String lastname,
        /** Total verified expense amount in DKK. */
        double totalExpenses,
        /** Number of verified expense entries. */
        int expenseCount,
        /** Expense category with highest spend for this consultant. */
        String topCategory,
        /** Amount in the top category in DKK. */
        double topCategoryAmount
) {}
