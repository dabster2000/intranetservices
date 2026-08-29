package dk.trustworks.intranet.dto.itbudget;

import dk.trustworks.intranet.model.enums.ItExpenseStatus;
import io.quarkus.runtime.annotations.RegisterForReflection;

import java.time.LocalDate;
import java.time.LocalDateTime;

/**
 * One registered piece of IT equipment, with the amortization state the browser
 * must not recompute.
 * <p>
 * An item consumes its <em>full</em> price from its invoice date until
 * {@code invoicedate + category.lifespan} months, then nothing — a cliff, not a
 * straight-line write-down (profile-view requirements §5.3). {@code expiryDate}
 * is that cliff; {@code countsTowardBudget} is whether the item is still on the
 * near side of it.
 *
 * @param expiryDate        null when the item's type has no lifespan configured,
 *                          or when the row has no invoice date — such an item
 *                          keeps counting rather than silently freeing budget
 * @param monthsRemaining   whole months from today to {@code expiryDate},
 *                          floored at 0; null whenever {@code expiryDate} is
 * @param expired           {@code expiryDate != null && !today.isBefore(expiryDate)}
 * @param countsTowardBudget {@code status == ACTIVE && !expired}
 */
@RegisterForReflection
public record ItExpenseItemDTO(
        int id,
        String useruuid,
        String description,
        int price,
        ItExpenseStatus status,
        LocalDate invoicedate,
        LocalDateTime createdAt,
        ItExpenseCategoryDTO category,
        LocalDate expiryDate,
        Integer monthsRemaining,
        boolean expired,
        boolean countsTowardBudget
) {}
