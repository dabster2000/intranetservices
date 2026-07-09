package dk.trustworks.intranet.aggregates.bonus.individual.jobs;

import dk.trustworks.intranet.aggregates.bonus.individual.services.IndividualBonusPayoutService;
import io.quarkus.scheduler.Scheduled;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import lombok.extern.jbosslog.JBossLog;

import java.time.LocalDate;

/**
 * Monthly job that materialises due individual-bonus payouts into salary_lump_sum before HR runs the
 * Danløn export for that month.
 * <p>
 * Runs the 20th at 06:00 — after the month's nightly fact_user_day / revenue refresh (so the basis is
 * final) and a few days before the payroll cut-off. Idempotent via source_reference, so it is safe
 * even though ECS-Express cutover can fire {@code @Scheduled} jobs twice around a deploy (the second
 * run is a no-op). The yearly true-up / yearly bonus stays admin-confirmed via the REST endpoint.
 */
@JBossLog
@ApplicationScoped
public class MaterializeDueBonusPayoutsJob {

    @Inject IndividualBonusPayoutService payoutService;

    @Scheduled(cron = "0 0 6 20 * ?") // 20th of each month at 06:00
    public void materializeMonthlyPayouts() {
        LocalDate month = LocalDate.now().withDayOfMonth(1);
        log.infof("Individual bonus monthly materialisation job starting for %s", month);
        int created = payoutService.materializeDue(month);
        log.infof("Individual bonus monthly materialisation job finished for %s: %d created", month, created);
    }
}
