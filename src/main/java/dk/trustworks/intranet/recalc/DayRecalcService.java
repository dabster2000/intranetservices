package dk.trustworks.intranet.recalc;

import dk.trustworks.intranet.bi.services.BudgetCalculatingExecutor;
import dk.trustworks.intranet.bi.services.UserAvailabilityCalculatorService;
import dk.trustworks.intranet.bi.services.UserSalaryCalculatorService;
import dk.trustworks.intranet.bi.services.WorkAggregateService;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import lombok.extern.jbosslog.JBossLog;

import java.time.LocalDate;

import static dk.trustworks.intranet.recalc.RecalcMappings.orderedStagesFrom;
import static dk.trustworks.intranet.recalc.RecalcMappings.targetsFor;

@ApplicationScoped
@JBossLog
public class DayRecalcService {

    @Inject UserSalaryCalculatorService salaryService;
    @Inject WorkAggregateService workAggService;
    @Inject UserAvailabilityCalculatorService availabilityService;
    @Inject BudgetCalculatingExecutor budgetService;

    public RecalcResult recalc(String userUuid, LocalDate day, RecalcTrigger trigger) {
        var targets = targetsFor(trigger);
        var start   = targets.startStage().orElse(PipelineStage.WORK_AGGREGATES);

        var result = new RecalcResult(trigger, userUuid, day);

        // Salary track
        if (targets.set().contains(Target.SALARY)) {
            try {
                salaryService.recalculateSalary(userUuid, day);
                result.merge(StageResult.ok("salary ok", true));
            } catch (Exception e) {
                log.errorf(e, "Salary recalculation failed for user %s day %s", userUuid, day);
                result.merge(StageResult.failed("salary failed", e));
            }
        }

        // Operations pipeline
        if (targets.set().contains(Target.OPERATIONS)) {
            for (var stage : orderedStagesFrom(start)) {
                try {
                    switch (stage) {
                        case WORK_AGGREGATES -> {
                            workAggService.recalculateWork(userUuid, day);
                            result.merge(StageResult.ok("workAggregates ok", true));
                        }
                        case AVAILABILITY -> {
                            availabilityService.updateUserAvailabilityByDay(userUuid, day);
                            result.merge(StageResult.ok("availability ok", true));
                        }
                        case BUDGET -> {
                            budgetService.recalculateUserDailyBudgets(userUuid, day);
                            result.merge(StageResult.ok("budget ok", true));
                        }
                    }
                } catch (Exception e) {
                    log.errorf(e, "Stage %s failed for user %s day %s", stage, userUuid, day);
                    result.merge(StageResult.failed(stage.name() + " failed", e));
                    break; // stop downstream on failure
                }
            }
        }

        return result;
    }
}
