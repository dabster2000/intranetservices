package dk.trustworks.intranet.knowledgeservice.model;

/**
 * Per-calendar-year knowledge (CKO) budget summary for one user.
 *
 * Every employee is granted the same yearly knowledge budget
 * (12 x {@link dk.trustworks.intranet.knowledgeservice.services.CkoExpenseService#MONTHLY_BUDGET} DKK).
 * Spend counts BOOKED and COMPLETED expenses by the calendar year of their eventdate;
 * WISHLIST entries are excluded. {@code remaining} can be negative when over budget.
 */
public record KnowledgeBudgetSummary(int year, int budget, int spent, int remaining) {
}
