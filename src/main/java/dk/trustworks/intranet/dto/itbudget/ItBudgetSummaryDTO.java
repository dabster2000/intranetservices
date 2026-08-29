package dk.trustworks.intranet.dto.itbudget;

import io.quarkus.runtime.annotations.RegisterForReflection;

import java.time.LocalDate;
import java.util.List;

/**
 * Everything the IT budget screens need, computed once in the backend. No BFF
 * route and no component may re-derive an expiry, a months-remaining or a used
 * budget from these fields — that divergence is what let 291 live items render a
 * green "Active" badge while contributing nothing to the total.
 *
 * @param itBudget          duplicate of {@code totalBudget}. The endpoint used to
 *                          answer {@code {"itBudget": n}} and callers still read
 *                          that key; keeping it costs one int and breaks nobody
 * @param availableBudget   {@code totalBudget - usedBudget}. Deliberately not
 *                          clamped at zero: overspend must be visible
 * @param nextReleaseDate   the earliest {@code expiryDate} among items still
 *                          counting, or null when nothing is counting
 * @param nextReleaseAmount the budget freed on {@code nextReleaseDate} (all items
 *                          amortizing that same day), or null
 */
@RegisterForReflection
public record ItBudgetSummaryDTO(
        int itBudget,
        int totalBudget,
        int usedBudget,
        int availableBudget,
        boolean canAddEquipment,
        ItBudgetSource budgetSource,
        LocalDate nextReleaseDate,
        Integer nextReleaseAmount,
        List<ItExpenseItemDTO> items,
        List<ItExpenseCategoryDTO> categories
) {

    /**
     * Derives every dependent field from the three inputs that are actually
     * resolved, so {@code itBudget}, {@code availableBudget} and
     * {@code canAddEquipment} cannot drift apart at a call site.
     */
    public static ItBudgetSummaryDTO of(int totalBudget,
                                        int usedBudget,
                                        ItBudgetSource budgetSource,
                                        LocalDate nextReleaseDate,
                                        Integer nextReleaseAmount,
                                        List<ItExpenseItemDTO> items,
                                        List<ItExpenseCategoryDTO> categories) {
        return new ItBudgetSummaryDTO(
                totalBudget,
                totalBudget,
                usedBudget,
                totalBudget - usedBudget,
                totalBudget > 0,
                budgetSource,
                nextReleaseDate,
                nextReleaseAmount,
                items,
                categories);
    }
}
