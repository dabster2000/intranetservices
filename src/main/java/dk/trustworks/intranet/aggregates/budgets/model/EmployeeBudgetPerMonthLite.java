package dk.trustworks.intranet.aggregates.budgets.model;

import com.fasterxml.jackson.annotation.JsonInclude;

/**
 * Lightweight projection of {@link EmployeeBudgetPerMonth} for high-volume list
 * endpoints (e.g. {@code GET /users/budgets/lite}).
 *
 * <p>The full {@code EmployeeBudgetPerMonth} embeds the complete {@code User},
 * {@code Client}, {@code Company} and {@code Contract} entities on every row.
 * On a full-period scan that is one row per (user &times; month &times; client
 * &times; contract), so the same heavy entity graphs re-serialize hundreds of
 * times (≈ multi-MB payloads). Every current consumer of the full-list endpoint
 * only reads a handful of scalars (uuids, client name, hours, rate), so this
 * projection ships just those.
 *
 * <p>The nested {@link Ref}/{@link ClientRef} shape is intentional: it mirrors
 * the {@code {uuid}} / {@code {uuid,name}} structure the existing BFF consumers
 * already read ({@code budget.user.uuid}, {@code budget.client.name},
 * {@code budget.contract.uuid}), so they need no field-access changes.
 *
 * <p>Because it carries no {@code User} entity, it also bypasses the reflective
 * field-stripping done by {@code UserScopeResponseFilter} on the full endpoint.
 *
 * <p>JK Team 2.0 WP3: with {@code includeInternal=true} the endpoint also returns
 * internal-assignment rows — {@code client = null}, {@code contract = null},
 * {@code rate = 0}, {@code kind = "INTERNAL"} and an {@link InternalAssignmentRef}.
 * Both new fields are omitted from the JSON of ordinary contract rows, so callers
 * that never asked for internal rows see byte-identical output.
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record EmployeeBudgetPerMonthLite(
        int year,
        int month,
        Ref user,
        ClientRef client,
        Ref contract,
        double budgetHours,
        double rate,
        /** {@code "INTERNAL"} for internal-assignment rows; absent for contract rows. */
        String kind,
        /** Present on internal rows only. */
        InternalAssignmentRef internalAssignment
) {

    public static final String KIND_INTERNAL = "INTERNAL";

    public record Ref(String uuid) {}

    public record ClientRef(String uuid, String name) {}

    public record InternalAssignmentRef(String uuid, String title, boolean strategic) {}

    public static EmployeeBudgetPerMonthLite from(EmployeeBudgetPerMonth b) {
        return new EmployeeBudgetPerMonthLite(
                b.getYear(),
                b.getMonth(),
                b.getUser() == null ? null : new Ref(b.getUser().getUuid()),
                b.getClient() == null ? null : new ClientRef(b.getClient().getUuid(), b.getClient().getName()),
                b.getContract() == null ? null : new Ref(b.getContract().getUuid()),
                b.getBudgetHours(),
                b.getRate(),
                null,
                null
        );
    }

    /** An internal-assignment row: demand, never revenue. */
    public static EmployeeBudgetPerMonthLite internal(int year, int month, String useruuid, double budgetHours,
                                                      String assignmentUuid, String title, boolean strategic) {
        return new EmployeeBudgetPerMonthLite(
                year,
                month,
                new Ref(useruuid),
                null,
                null,
                budgetHours,
                0.0,
                KIND_INTERNAL,
                new InternalAssignmentRef(assignmentUuid, title, strategic)
        );
    }
}
