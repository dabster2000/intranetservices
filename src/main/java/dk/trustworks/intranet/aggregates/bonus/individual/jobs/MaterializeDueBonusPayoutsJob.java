package dk.trustworks.intranet.aggregates.bonus.individual.jobs;

import dk.trustworks.intranet.aggregates.bonus.individual.services.IndividualBonusPayoutService;
import io.quarkus.scheduler.Scheduled;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import lombok.extern.jbosslog.JBossLog;

import java.time.LocalDate;

/**
 * Monthly job that materialises due individual-bonus MONTHLY / ADVANCE payouts into salary_lump_sum.
 * <p>
 * Runs the 3rd of each month at 06:00 and targets the month that JUST CLOSED (now − 1 month). This is
 * deliberately AFTER that month's end and its nightly fact_user_day / revenue refresh (which run in the
 * ~02:00–05:00 window) have fully settled the basis, so the write-once, idempotent advance amount is
 * FINAL rather than frozen half-computed mid-month.
 * <p>
 * Implication of running after month-end: a month's advance is materialised in the FOLLOWING payroll
 * cycle — a one-cycle lag traded deliberately for correctness (final facts over immediacy). Advances are
 * on-account; the year-end true-up reconciles any residue, and the read-time projection still shows the
 * up-to-date estimate meanwhile. Idempotent via source_reference, so an ECS-Express cutover firing the
 * job twice around a deploy is a no-op. The YEARLY true-up / yearly bonus stays admin-confirmed via the
 * {@code /payouts/run} endpoint (this job passes {@code scheduledOnly = true}).
 */
@JBossLog
@ApplicationScoped
public class MaterializeDueBonusPayoutsJob {

    @Inject IndividualBonusPayoutService payoutService;

    @Scheduled(cron = "0 0 6 3 * ?") // 3rd of each month at 06:00 — after the prior month's fact refresh
    public void materializeMonthlyPayouts() {
        LocalDate month = LocalDate.now().minusMonths(1).withDayOfMonth(1); // the month that just closed
        log.infof("Individual bonus monthly materialisation job starting for %s", month);
        int created = payoutService.materializeDue(month, true); // scheduledOnly: advances/monthly only
        log.infof("Individual bonus monthly materialisation job finished for %s: %d created", month, created);
    }
}
