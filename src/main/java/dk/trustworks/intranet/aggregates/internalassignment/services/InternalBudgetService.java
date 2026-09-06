package dk.trustworks.intranet.aggregates.internalassignment.services;

import dk.trustworks.intranet.aggregates.budgets.model.EmployeeBudgetPerMonthLite;
import dk.trustworks.intranet.aggregates.internalassignment.model.InternalAssignment;
import dk.trustworks.intranet.aggregates.internalassignment.model.InternalBudgetPerDayAggregate;
import jakarta.enterprise.context.ApplicationScoped;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * The read-layer union for internal demand (spec §4.3.4): {@code fact_internal_budget_day}
 * rolled up to (year, month, user, assignment) in the {@link EmployeeBudgetPerMonthLite}
 * shape, with {@code client = null}, {@code contract = null}, {@code rate = 0} and
 * {@code kind = "INTERNAL"}. Only callers that ask ({@code includeInternal=true}) get them.
 */
@ApplicationScoped
public class InternalBudgetService {

    /** Internal rows for every user in {@code [from, to)}, one per (month, user, assignment). */
    public List<EmployeeBudgetPerMonthLite> liteInPeriod(LocalDate from, LocalDate to) {
        return rollUp(InternalBudgetPerDayAggregate.findInPeriod(from, to));
    }

    /** Internal rows for one user in {@code [from, to)}. */
    public List<EmployeeBudgetPerMonthLite> liteForUser(String useruuid, LocalDate from, LocalDate to) {
        return rollUp(InternalBudgetPerDayAggregate.findForUserInPeriod(useruuid, from, to));
    }

    /** Pure roll-up, package-private for the unit test. */
    static List<EmployeeBudgetPerMonthLite> rollUp(List<InternalBudgetPerDayAggregate> days) {
        record Key(int year, int month, String useruuid, String assignmentUuid) {}
        Map<Key, double[]> hours = new LinkedHashMap<>();
        Map<Key, Boolean> strategic = new HashMap<>();
        for (InternalBudgetPerDayAggregate d : days) {
            Key key = new Key(d.getYear(), d.getMonth(), d.getUseruuid(), d.getInternalAssignmentUuid());
            hours.computeIfAbsent(key, k -> new double[1])[0] += d.getBudgetHours();
            strategic.putIfAbsent(key, d.isStrategic());
        }
        Map<String, String> titles = new HashMap<>();
        for (Key key : hours.keySet()) {
            titles.computeIfAbsent(key.assignmentUuid(), uuid -> {
                InternalAssignment a = InternalAssignment.findById(uuid);
                return a == null ? "Internal assignment" : a.getTitle();
            });
        }
        List<EmployeeBudgetPerMonthLite> out = new ArrayList<>(hours.size());
        for (Map.Entry<Key, double[]> e : hours.entrySet()) {
            Key k = e.getKey();
            out.add(EmployeeBudgetPerMonthLite.internal(
                    k.year(), k.month(), k.useruuid(), round2(e.getValue()[0]),
                    k.assignmentUuid(), titles.get(k.assignmentUuid()), strategic.getOrDefault(k, false)));
        }
        return out;
    }

    private static double round2(double v) {
        return Math.round(v * 100.0) / 100.0;
    }
}
