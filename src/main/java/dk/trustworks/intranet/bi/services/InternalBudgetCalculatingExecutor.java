package dk.trustworks.intranet.bi.services;

import dk.trustworks.intranet.aggregates.bidata.model.BiDataPerDay;
import dk.trustworks.intranet.aggregates.budgets.model.EmployeeBudgetPerDayAggregate;
import dk.trustworks.intranet.aggregates.internalassignment.model.InternalAssignment;
import dk.trustworks.intranet.aggregates.internalassignment.model.InternalBudgetPerDayAggregate;
import dk.trustworks.intranet.domain.user.entity.User;
import dk.trustworks.intranet.utils.DateUtils;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.transaction.Transactional;
import lombok.extern.jbosslog.JBossLog;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;

/**
 * The internal-assignment counterpart of {@link BudgetCalculatingExecutor} (spec §4.3.3):
 * per (user, day), delete the old {@code fact_internal_budget_day} rows and write one per
 * APPROVED assignment covering the day.
 *
 * <p>Daily spread mirrors the contract path — {@code hours_per_week / 5} on weekdays — and
 * then stacks against the day's net availability <em>after</em> the contract budgets: client
 * work is planned first, internal work fills what is left. When the approved internal hours
 * exceed that remainder they are scaled down proportionally, exactly as contracts are
 * normalised among themselves. Runs after the contract budget step in
 * {@link UserDayRecalculationService}, so the contract rows it reads are fresh.
 */
@JBossLog
@ApplicationScoped
public class InternalBudgetCalculatingExecutor {

    /** One assignment's share of a day, before and after stacking. */
    public record Spread(String assignmentUuid, boolean strategic, double unadjusted, double adjusted) {}

    @Transactional
    public void recalculateUserDailyInternalBudgets(String useruuid, LocalDate testDay) {
        if (useruuid == null || testDay == null) {
            return;
        }
        InternalBudgetPerDayAggregate.deleteForUserAndDay(useruuid, testDay);
        if (DateUtils.isWeekend(testDay)) {
            return;
        }
        List<InternalAssignment> approved = InternalAssignment.findApprovedOnDay(useruuid, testDay);
        if (approved.isEmpty()) {
            return;
        }
        User user = User.findById(useruuid);
        if (user == null) {
            log.warnf("recalculateUserDailyInternalBudgets: user not found, skipping. useruuid=%s day=%s", useruuid, testDay);
            return;
        }

        BiDataPerDay availability = BiDataPerDay.<BiDataPerDay>find("documentDate = ?1 and user = ?2", testDay, user)
                .firstResultOptional().orElse(null);
        double netAvailable = availability == null ? 0.0 : availability.getNetAvailableHours();
        String companyuuid = availability == null || availability.company == null ? null : availability.company.getUuid();

        double contractBudget = EmployeeBudgetPerDayAggregate.<EmployeeBudgetPerDayAggregate>list(
                        "documentDate = ?1 and user = ?2", testDay, user)
                .stream().mapToDouble(EmployeeBudgetPerDayAggregate::getBudgetHours).sum();

        List<Spread> spreads = spread(approved, Math.max(0.0, netAvailable - contractBudget));
        List<InternalBudgetPerDayAggregate> rows = new ArrayList<>(spreads.size());
        for (Spread s : spreads) {
            if (s.unadjusted() <= 0.0) continue;
            rows.add(new InternalBudgetPerDayAggregate(useruuid, testDay, s.assignmentUuid(), companyuuid,
                    s.adjusted(), s.unadjusted(), s.strategic()));
        }
        if (!rows.isEmpty()) {
            InternalBudgetPerDayAggregate.persist(rows);
        }
    }

    /**
     * Pure stacking rule: each approved assignment claims {@code hours_per_week / 5}; when the
     * claims together exceed {@code remainingCapacity} every claim is scaled by the same factor.
     */
    public static List<Spread> spread(List<InternalAssignment> approved, double remainingCapacity) {
        List<Spread> out = new ArrayList<>(approved.size());
        double sum = 0.0;
        for (InternalAssignment a : approved) {
            double daily = a.getHoursPerWeek() == null ? 0.0 : a.getHoursPerWeek().doubleValue() / 5.0;
            sum += daily;
            out.add(new Spread(a.getUuid(), a.isStrategic(), daily, daily));
        }
        double capacity = Math.max(0.0, remainingCapacity);
        if (sum > capacity && sum > 0.0) {
            double factor = capacity / sum;
            List<Spread> scaled = new ArrayList<>(out.size());
            for (Spread s : out) {
                scaled.add(new Spread(s.assignmentUuid(), s.strategic(), s.unadjusted(), round4(s.unadjusted() * factor)));
            }
            return scaled;
        }
        return out;
    }

    private static double round4(double v) {
        return Math.round(v * 10_000.0) / 10_000.0;
    }
}
