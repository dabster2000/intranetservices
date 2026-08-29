package dk.trustworks.intranet.dto.itbudget;

import dk.trustworks.intranet.model.ItExpenseCategory;
import io.quarkus.runtime.annotations.RegisterForReflection;

/**
 * An IT equipment type as it appears on the wire. {@code lifespan} is the
 * amortization length in months.
 * <p>
 * A DTO rather than the entity because {@code ItExpenseItem.category} is a lazy
 * {@code @ManyToOne}: handing the proxy to Jackson binds serialization to
 * whether a session happens to still be open. Mapping here forces the read
 * inside the service, where it belongs.
 */
@RegisterForReflection
public record ItExpenseCategoryDTO(
        int id,
        String name,
        String longName,
        int lifespan,
        String description
) {

    public static ItExpenseCategoryDTO from(ItExpenseCategory category) {
        if (category == null) return null;
        return new ItExpenseCategoryDTO(
                category.getId(),
                category.getName(),
                category.getLongName(),
                category.getLifespan(),
                category.getDescription());
    }
}
