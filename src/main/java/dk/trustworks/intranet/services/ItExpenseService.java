package dk.trustworks.intranet.services;

import dk.trustworks.intranet.aggregates.users.services.UserService;
import dk.trustworks.intranet.domain.user.entity.User;
import dk.trustworks.intranet.domain.user.entity.UserStatus;
import dk.trustworks.intranet.dto.itbudget.CreateItExpenseRequest;
import dk.trustworks.intranet.dto.itbudget.ItBudgetSource;
import dk.trustworks.intranet.dto.itbudget.ItBudgetSummaryDTO;
import dk.trustworks.intranet.dto.itbudget.ItExpenseCategoryDTO;
import dk.trustworks.intranet.dto.itbudget.ItExpenseItemDTO;
import dk.trustworks.intranet.dto.itbudget.UpdateItExpenseRequest;
import dk.trustworks.intranet.model.ItExpenseCategory;
import dk.trustworks.intranet.model.ItExpenseItem;
import dk.trustworks.intranet.model.enums.ItExpenseStatus;
import dk.trustworks.intranet.userservice.model.enums.ConsultantType;
import lombok.extern.jbosslog.JBossLog;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.transaction.Transactional;
import jakarta.ws.rs.BadRequestException;
import jakarta.ws.rs.NotFoundException;
import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.Comparator;
import java.util.EnumSet;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;

/**
 * The one place the IT budget is computed. A registered item consumes its
 * <em>full</em> price from its invoice date until
 * {@code invoicedate + category.lifespan} months and nothing afterwards — a
 * cliff, not a straight-line write-down (profile-view requirements §5.3).
 * <p>
 * That rule used to be re-implemented in two BFF routes and a React component,
 * each with its own arithmetic: one approximated a month as 30 days, another
 * used JS {@code setMonth} (which rolls 31 Aug + 30 months into 3 March), and a
 * third invented a 30-month default for types that have none. They disagreed
 * with each other and with the badge shown next to them. Everything now derives
 * from {@link #toItemDTO} here, and callers render what they are given.
 * <p>
 * The date/amount rules are static and package-visible so they run in the
 * DB-free fast tier, the way {@code TeamSettingService.resolveBudget} is.
 */
@JBossLog
@ApplicationScoped
public class ItExpenseService {

    /** {@code itbudget.description varchar(100)} in the live DDL. */
    static final int DESCRIPTION_MAX = 100;

    /** Older than the company. An invoice date below this is a typo, not history. */
    static final LocalDate EARLIEST_INVOICE_DATE = LocalDate.of(2000, 1, 1);

    /**
     * A week of slack for equipment invoiced just ahead of delivery. A live row
     * is dated 2030-09-26 — a mistyped year that nobody can correct through the
     * UI and that pins that employee's budget for four more years.
     */
    static final int MAX_INVOICE_DAYS_AHEAD = 7;

    /** Consultant types that carry no IT budget at all. */
    static final Set<ConsultantType> NO_BUDGET_TYPES = EnumSet.of(ConsultantType.STUDENT, ConsultantType.EXTERNAL);

    /** US-IT-002: newest invoice date first, rows without one last. */
    static final Comparator<ItExpenseItemDTO> NEWEST_INVOICE_FIRST =
            Comparator.comparing(ItExpenseItemDTO::invoicedate,
                            Comparator.nullsLast(Comparator.reverseOrder()))
                    .thenComparing(ItExpenseItemDTO::id, Comparator.reverseOrder());

    @Inject
    UserService userService;

    @Inject
    TeamSettingService teamSettingService;

    @Inject
    ItExpenseCategoryService categoryService;

    // ── Reads ─────────────────────────────────────────────────────────────

    /**
     * Exact match on {@code useruuid}. This was {@code useruuid like ?1} against
     * a path parameter, so a caller passing {@code %} was handed every
     * employee's equipment.
     */
    public List<ItExpenseItemDTO> findExpensesByUseruuid(String useruuid) {
        return findExpensesByUseruuid(useruuid, LocalDate.now());
    }

    List<ItExpenseItemDTO> findExpensesByUseruuid(String useruuid, LocalDate today) {
        // Fetch join: the category is lazy and every row's lifespan is needed.
        List<ItExpenseItem> rows = ItExpenseItem
                .find("select i from ItExpenseItem i left join fetch i.category where i.useruuid = ?1", useruuid)
                .list();
        return rows.stream()
                .map(item -> toItemDTO(item, today))
                .sorted(NEWEST_INVOICE_FIRST)
                .toList();
    }

    /**
     * The full budget picture for one user: what they have, what they have
     * spent, when the next tranche frees up, and why the total is what it is.
     */
    public ItBudgetSummaryDTO getBudgetSummary(String useruuid) {
        LocalDate today = LocalDate.now();
        List<ItExpenseItemDTO> items = findExpensesByUseruuid(useruuid, today);
        List<ItExpenseCategoryDTO> categories = categoryService.findAll().stream()
                .map(ItExpenseCategoryDTO::from)
                .toList();

        boolean noBudgetType = hasNoBudgetConsultantType(useruuid, today);
        TeamSettingService.ResolvedItBudget resolved = teamSettingService.resolveItBudget(useruuid);
        ItBudgetSource source = noBudgetType
                ? ItBudgetSource.NO_BUDGET_CONSULTANT_TYPE
                : resolved.fromTeamRow() ? ItBudgetSource.TEAM : ItBudgetSource.DEFAULT;
        int totalBudget = noBudgetType ? 0 : resolved.amount();

        Optional<NextRelease> nextRelease = nextRelease(items);
        return ItBudgetSummaryDTO.of(
                totalBudget,
                usedBudget(items),
                source,
                nextRelease.map(NextRelease::date).orElse(null),
                nextRelease.map(NextRelease::amount).orElse(null),
                items,
                categories);
    }

    /**
     * STUDENT and EXTERNAL consultants have no IT budget — a rule that lived in
     * the BFF, where it was applied to the profile page and not to the
     * employee-management tab, so the two screens disagreed. Resolved from the
     * user's current status the same way every other caller does
     * ({@code User.getUserStatus}), which already falls back to a synthetic
     * STAFF/TERMINATED row for a user with no status history.
     * <p>
     * TEAM versus DEFAULT is answered by {@link TeamSettingService#resolveItBudget},
     * which owns the resolution rule and is the only thing that can say whether
     * the amount it returned actually came from a stored team row.
     */
    private boolean hasNoBudgetConsultantType(String useruuid, LocalDate today) {
        User user = userService.findById(useruuid, false);
        if (user == null) return false;
        UserStatus status = userService.getUserStatus(user, today);
        return status != null && NO_BUDGET_TYPES.contains(status.getType());
    }

    // ── Mutations ─────────────────────────────────────────────────────────

    @Transactional
    public ItExpenseItemDTO createExpense(String useruuid, CreateItExpenseRequest request) {
        LocalDate today = LocalDate.now();
        if (request == null) throw new BadRequestException("A request body is required");

        ItExpenseItem item = new ItExpenseItem();
        item.setUseruuid(useruuid);
        item.setCategory(requireCategory(request.categoryId()));
        item.setDescription(requireDescription(request.description()));
        item.setPrice(requirePrice(request.price()));
        item.setInvoicedate(requireInvoicedate(request.invoicedate(), today));
        // Never from the wire: a newly registered item is ACTIVE, and only the
        // amortization job or a manager moves it off that.
        item.setStatus(ItExpenseStatus.ACTIVE);

        ItExpenseItem.persist(item);
        return toItemDTO(item, today);
    }

    /**
     * Writes only the fields the caller actually supplied, through the managed
     * entity. A bulk {@code update(...)} here is what NULLed description, price
     * and invoice date on seven live rows whose owner only meant to say
     * "Broken".
     */
    @Transactional
    public ItExpenseItemDTO updateExpense(String useruuid, int id, UpdateItExpenseRequest request) {
        LocalDate today = LocalDate.now();
        if (request == null) throw new BadRequestException("A request body is required");

        ItExpenseItem item = findOwned(useruuid, id);
        ItExpenseCategory category = request.categoryId() == null ? null : requireCategory(request.categoryId());
        applySuppliedFields(item, request, category, today);
        return toItemDTO(item, today);
    }

    @Transactional
    public void deleteExpense(String useruuid, int id) {
        findOwned(useruuid, id).delete();
    }

    /**
     * Flips ACTIVE items past their amortization date to AMORTIZED and returns
     * how many changed. Touches nothing but the status, and re-running it is a
     * no-op because an AMORTIZED row is no longer selected.
     */
    @Transactional
    public int amortizeDueItems(LocalDate today) {
        List<ItExpenseItem> active = ItExpenseItem
                .find("select i from ItExpenseItem i left join fetch i.category where i.status = ?1",
                        ItExpenseStatus.ACTIVE)
                .list();
        int amortized = 0;
        for (ItExpenseItem item : active) {
            if (!isExpired(expiryDate(item.getInvoicedate(), lifespanOf(item.getCategory())), today)) continue;
            item.setStatus(ItExpenseStatus.AMORTIZED);
            amortized++;
        }
        return amortized;
    }

    /**
     * The owner predicate. Update and delete used to key on the row id alone,
     * so any {@code devices:write} holder could mutate anyone's equipment by
     * guessing an integer. A foreign row is reported as absent rather than
     * forbidden — the caller must not learn that the id exists.
     */
    private ItExpenseItem findOwned(String useruuid, int id) {
        ItExpenseItem item = ItExpenseItem.findById(id);
        if (!isOwnedBy(item, useruuid)) throw new NotFoundException("IT expense not found: " + id);
        return item;
    }

    private ItExpenseCategory requireCategory(Integer categoryId) {
        if (categoryId == null || categoryId <= 0) throw new BadRequestException("categoryId is required");
        ItExpenseCategory category = ItExpenseCategory.findById(categoryId);
        if (category == null) throw new BadRequestException("Unknown categoryId: " + categoryId);
        return category;
    }

    // ── Pure helpers ──────────────────────────────────────────────────────
    // Static and package-visible so the amortization and budget rules can be
    // unit-tested without a database.

    static boolean isOwnedBy(ItExpenseItem item, String useruuid) {
        return item != null && item.getUseruuid() != null && item.getUseruuid().equals(useruuid);
    }

    /**
     * The PATCH semantics of the PUT: a field the caller left out keeps its
     * stored value. {@code category} is the already-resolved type, or null when
     * the caller sent no {@code categoryId}.
     */
    static void applySuppliedFields(ItExpenseItem item, UpdateItExpenseRequest request,
                                    ItExpenseCategory category, LocalDate today) {
        if (category != null) item.setCategory(category);
        if (request.description() != null) item.setDescription(requireDescription(request.description()));
        if (request.price() != null) item.setPrice(requirePrice(request.price()));
        if (request.invoicedate() != null) item.setInvoicedate(requireInvoicedate(request.invoicedate(), today));
        if (request.status() != null) item.setStatus(request.status());
    }

    /**
     * Null when the type carries no amortization length. Rule 6 of the
     * remediation: never guess a default — an item whose type has no lifespan
     * keeps counting until somebody configures one.
     */
    static Integer lifespanOf(ItExpenseCategory category) {
        if (category == null || category.getLifespan() <= 0) return null;
        return category.getLifespan();
    }

    /**
     * {@code LocalDate.plusMonths} clamps month-ends the way a human would:
     * 31 Aug + 30 months is 28/29 Feb, where the JS {@code setMonth} this
     * replaces produced 3 March.
     */
    static LocalDate expiryDate(LocalDate invoicedate, Integer lifespanMonths) {
        if (invoicedate == null || lifespanMonths == null || lifespanMonths <= 0) return null;
        return invoicedate.plusMonths(lifespanMonths);
    }

    /** The item stops counting on its expiry date, not the day after. */
    static boolean isExpired(LocalDate expiryDate, LocalDate today) {
        return expiryDate != null && !today.isBefore(expiryDate);
    }

    /** Whole calendar months, never an approximation of 30 days. */
    static Integer monthsRemaining(LocalDate expiryDate, LocalDate today) {
        if (expiryDate == null) return null;
        return (int) Math.max(0, ChronoUnit.MONTHS.between(today, expiryDate));
    }

    static ItExpenseItemDTO toItemDTO(ItExpenseItem item, LocalDate today) {
        LocalDate expiryDate = expiryDate(item.getInvoicedate(), lifespanOf(item.getCategory()));
        boolean expired = isExpired(expiryDate, today);
        return new ItExpenseItemDTO(
                item.getId(),
                item.getUseruuid(),
                item.getDescription(),
                item.getPrice(),
                item.getStatus(),
                item.getInvoicedate(),
                item.getCreatedAt(),
                ItExpenseCategoryDTO.from(item.getCategory()),
                expiryDate,
                monthsRemaining(expiryDate, today),
                expired,
                item.getStatus() == ItExpenseStatus.ACTIVE && !expired);
    }

    /**
     * A negative price is clamped to zero rather than subtracted: a credit note
     * keyed in as a negative row must not hand the employee extra budget.
     */
    static int usedBudget(List<ItExpenseItemDTO> items) {
        return items.stream()
                .filter(ItExpenseItemDTO::countsTowardBudget)
                .mapToInt(item -> Math.max(0, item.price()))
                .sum();
    }

    /** The next date on which budget frees up, and how much frees up that day. */
    record NextRelease(LocalDate date, int amount) {}

    static Optional<NextRelease> nextRelease(List<ItExpenseItemDTO> items) {
        Optional<LocalDate> earliest = items.stream()
                .filter(ItExpenseItemDTO::countsTowardBudget)
                .map(ItExpenseItemDTO::expiryDate)
                .filter(Objects::nonNull)
                .min(Comparator.naturalOrder());
        return earliest.map(date -> new NextRelease(date, items.stream()
                .filter(ItExpenseItemDTO::countsTowardBudget)
                .filter(item -> date.equals(item.expiryDate()))
                // Same clamp as usedBudget, so the released amount reconciles
                // with the total it is released from.
                .mapToInt(item -> Math.max(0, item.price()))
                .sum()));
    }

    // ── Validation ────────────────────────────────────────────────────────

    static String requireDescription(String description) {
        String clean = description == null ? "" : description.trim();
        if (clean.isEmpty()) throw new BadRequestException("description is required");
        if (clean.length() > DESCRIPTION_MAX) {
            throw new BadRequestException("description must be at most " + DESCRIPTION_MAX + " characters");
        }
        return clean;
    }

    static int requirePrice(Integer price) {
        if (price == null) throw new BadRequestException("price is required");
        if (price <= 0) throw new BadRequestException("price must be greater than 0");
        return price;
    }

    static LocalDate requireInvoicedate(LocalDate invoicedate, LocalDate today) {
        if (invoicedate == null) throw new BadRequestException("invoicedate is required");
        if (invoicedate.isBefore(EARLIEST_INVOICE_DATE)) {
            throw new BadRequestException("invoicedate must not be before " + EARLIEST_INVOICE_DATE);
        }
        if (invoicedate.isAfter(today.plusDays(MAX_INVOICE_DAYS_AHEAD))) {
            throw new BadRequestException(
                    "invoicedate must not be more than " + MAX_INVOICE_DAYS_AHEAD + " days in the future");
        }
        return invoicedate;
    }
}
