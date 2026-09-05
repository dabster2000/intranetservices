package dk.trustworks.intranet.bi.services;

import dk.trustworks.intranet.aggregates.availability.config.DeclaredAvailabilityPolicy;
import dk.trustworks.intranet.aggregates.availability.model.UserDeclaredAvailability;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.context.control.ActivateRequestContext;
import jakarta.inject.Inject;
import lombok.extern.jbosslog.JBossLog;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.Collection;
import java.util.Collections;
import java.util.HashMap;
import java.util.Map;
import java.util.TreeSet;

/**
 * The per-{@code (user, day)} recalculation pipeline: availability → work → budget → salary.
 *
 * <p>This is the same sequence {@code UserDayOrchestrationWriter} runs for every item of the
 * nightly {@code budget-aggregation} job — the writer now delegates here — so a declared
 * availability write (WP1) or an internal-assignment decision (WP3) refreshes a day through
 * exactly one code path. There is deliberately no second queue: targeted callers hand this
 * service the affected days and it runs the same four steps the batch would run overnight.
 *
 * <p>Declarations are batch-loaded per user per range ({@link #preloadDeclarations}) and
 * handed to the availability step, never looked up one query per day inside the loop. In
 * {@code SHADOW} mode nothing is loaded at all — the resolver does not consult the table.
 */
@JBossLog
@ApplicationScoped
public class UserDayRecalculationService {

    /**
     * Ceiling on the days one targeted write recalculates immediately. A bulk declaration of
     * a whole year still lands in {@code user_declared_availability}; days beyond the cap are
     * picked up by the nightly job, whose window is −2..+2 months anyway.
     */
    public static final int MAX_IMMEDIATE_DAYS = 200;

    @Inject
    UserAvailabilityCalculatorService availabilityService;

    @Inject
    WorkAggregateService workAggregateService;

    @Inject
    BudgetCalculatingExecutor budgetCalculatingExecutor;

    @Inject
    UserSalaryCalculatorService userSalaryCalculatorService;

    @Inject
    DeclaredAvailabilityPolicy declaredAvailabilityPolicy;

    /**
     * The declarations the availability step will consult for {@code [from, to]}, keyed by day.
     * Empty in {@code SHADOW} mode — the resolver keeps {@code allocation / 5} for everyone and
     * must not pay for a lookup it will not use.
     */
    public Map<LocalDate, BigDecimal> preloadDeclarations(String useruuid, LocalDate from, LocalDate to) {
        if (!declaredAvailabilityPolicy.isLive() || useruuid == null || from == null || to == null) {
            return Collections.emptyMap();
        }
        Map<LocalDate, BigDecimal> byDay = new HashMap<>();
        for (UserDeclaredAvailability row : UserDeclaredAvailability.findForRange(useruuid, from, to)) {
            byDay.put(row.getDay(), row.getHours());
        }
        return byDay;
    }

    /**
     * Runs the four steps for one day. {@code declaredByDay} is the preloaded map for a range
     * that contains {@code day}; pass {@code null} to let the availability step look the day
     * up itself (single-day callers).
     */
    public void recalculateDay(String useruuid, LocalDate day, Map<LocalDate, BigDecimal> declaredByDay) {
        availabilityService.updateUserAvailabilityByDay(useruuid, day, declaredByDay);
        workAggregateService.recalculateWork(useruuid, day);
        budgetCalculatingExecutor.recalculateUserDailyBudgets(useruuid, day);
        userSalaryCalculatorService.recalculateSalary(useruuid, day);
    }

    /**
     * Targeted refresh after a write. Per-day failures are logged and skipped so one bad day
     * cannot leave the rest stale; the nightly job is the backstop for whatever this misses.
     *
     * @return the number of days recalculated
     */
    @ActivateRequestContext
    public int recalculateDays(String useruuid, Collection<LocalDate> days) {
        if (useruuid == null || days == null || days.isEmpty()) {
            return 0;
        }
        TreeSet<LocalDate> sorted = new TreeSet<>(days);
        if (sorted.size() > MAX_IMMEDIATE_DAYS) {
            log.warnf("recalculateDays: %d days requested for user=%s, capping at %d — the nightly job covers the rest",
                    sorted.size(), useruuid, MAX_IMMEDIATE_DAYS);
        }
        Map<LocalDate, BigDecimal> declared = preloadDeclarations(useruuid, sorted.first(), sorted.last());
        int done = 0;
        for (LocalDate day : sorted) {
            if (done >= MAX_IMMEDIATE_DAYS) break;
            try {
                recalculateDay(useruuid, day, declared);
                done++;
            } catch (Exception ex) {
                log.errorf(ex, "recalculateDays failed user=%s day=%s", useruuid, day);
            }
        }
        log.infof("recalculateDays: user=%s days=%d", useruuid, done);
        return done;
    }
}
