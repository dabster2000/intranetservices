package dk.trustworks.intranet.services;

import dk.trustworks.intranet.model.ItExpenseCategory;
import dk.trustworks.intranet.model.ItExpenseItem;
import lombok.extern.jbosslog.JBossLog;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.transaction.Transactional;
import jakarta.ws.rs.BadRequestException;
import jakarta.ws.rs.ClientErrorException;
import jakarta.ws.rs.NotFoundException;
import java.util.List;

/**
 * Application service over {@code itbudget_category} — the IT equipment types
 * and, per type, the {@code lifespan}: how many months an item of that type
 * keeps consuming the employee's IT budget before it is amortized.
 * <p>
 * The table predates Flyway in this repository, so the column limits enforced
 * here mirror the live DDL ({@code name varchar(25)}, {@code long_name
 * varchar(100)}, {@code description varchar(255)}) rather than a migration.
 * Without these checks MariaDB would cut the value short or reject the write
 * far from the caller; validating up front turns that into a 400 with a usable
 * message.
 */
@JBossLog
@ApplicationScoped
public class ItExpenseCategoryService {

    /** {@code name varchar(25)} in the live DDL. */
    static final int NAME_MAX = 25;
    /** {@code long_name varchar(100)} in the live DDL. */
    static final int LONG_NAME_MAX = 100;
    /** {@code description varchar(255)} in the live DDL. */
    static final int DESCRIPTION_MAX = 255;

    /**
     * A lifespan is a whole number of months. One month is the shortest thing
     * that can be amortized; 120 months (10 years) is far past any equipment
     * policy and catches a mistyped "3600" before it silently pins an
     * employee's budget for a lifetime.
     */
    static final int LIFESPAN_MIN = 1;
    static final int LIFESPAN_MAX = 120;

    public List<ItExpenseCategory> findAll() {
        return ItExpenseCategory.list("order by name");
    }

    public ItExpenseCategory findById(int id) {
        ItExpenseCategory category = ItExpenseCategory.findById(id);
        if (category == null) throw new NotFoundException("Equipment type not found: " + id);
        return category;
    }

    @Transactional
    public ItExpenseCategory createCategory(String name, String longName, int lifespan, String description) {
        String cleanName = requireName(name);
        if (ItExpenseCategory.count("lower(name) = ?1", cleanName.toLowerCase()) > 0) {
            throw new ClientErrorException("An equipment type named '" + cleanName + "' already exists", 409);
        }

        ItExpenseCategory category = new ItExpenseCategory();
        category.setName(cleanName);
        category.setLongName(optionalText(longName, LONG_NAME_MAX, "longName"));
        category.setDescription(optionalText(description, DESCRIPTION_MAX, "description"));
        category.setLifespan(requireLifespan(lifespan));
        ItExpenseCategory.persist(category);
        return category;
    }

    @Transactional
    public ItExpenseCategory updateCategory(int id, String name, String longName, int lifespan, String description) {
        ItExpenseCategory category = findById(id);

        String cleanName = requireName(name);
        if (ItExpenseCategory.count("lower(name) = ?1 and id <> ?2", cleanName.toLowerCase(), id) > 0) {
            throw new ClientErrorException("An equipment type named '" + cleanName + "' already exists", 409);
        }

        category.setName(cleanName);
        category.setLongName(optionalText(longName, LONG_NAME_MAX, "longName"));
        category.setDescription(optionalText(description, DESCRIPTION_MAX, "description"));
        category.setLifespan(requireLifespan(lifespan));
        return category;
    }

    /**
     * Deleting a type that registered equipment still points at would leave
     * {@code itbudget.category_id} dangling — there is no FK on that column, so
     * nothing else would stop it, and the budget math (which reads the type's
     * lifespan) would silently fall back to a default for every affected item.
     * Refuse with 409 and let the admin re-type those items first.
     */
    @Transactional
    public void deleteCategory(int id) {
        ItExpenseCategory category = findById(id);
        long inUse = ItExpenseItem.count("category.id = ?1", id);
        if (inUse > 0) {
            throw new ClientErrorException(
                    "Equipment type '" + category.getName() + "' is used by " + inUse
                            + " registered item(s) and cannot be deleted", 409);
        }
        category.delete();
    }

    // ── Validation ────────────────────────────────────────────────────────
    // Static and package-visible so the rules can be unit-tested without a
    // database, the way TeamSettingService.resolveBudget is.

    static String requireName(String name) {
        String clean = name == null ? "" : name.trim();
        if (clean.isEmpty()) throw new BadRequestException("name is required");
        if (clean.length() > NAME_MAX) {
            throw new BadRequestException("name must be at most " + NAME_MAX + " characters");
        }
        return clean;
    }

    static String optionalText(String value, int max, String field) {
        String clean = value == null ? "" : value.trim();
        if (clean.length() > max) {
            throw new BadRequestException(field + " must be at most " + max + " characters");
        }
        return clean.isEmpty() ? null : clean;
    }

    static int requireLifespan(int lifespan) {
        if (lifespan < LIFESPAN_MIN || lifespan > LIFESPAN_MAX) {
            throw new BadRequestException(
                    "lifespan must be between " + LIFESPAN_MIN + " and " + LIFESPAN_MAX + " months");
        }
        return lifespan;
    }
}
