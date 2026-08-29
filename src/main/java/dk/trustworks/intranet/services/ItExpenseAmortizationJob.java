package dk.trustworks.intranet.services;

import dk.trustworks.intranet.scheduling.SchedulerShutdownGuard;
import io.quarkus.scheduler.Scheduled;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.jboss.logging.Logger;

import java.time.LocalDate;

/**
 * Applies the amortization rule that nothing used to apply. An item stops
 * consuming its owner's IT budget once {@code invoicedate + category.lifespan}
 * months have passed; until now the row simply stayed ACTIVE forever, so the
 * budget was never released and the screen kept showing a green "Active" badge
 * on equipment that had long since amortized on paper.
 *
 * <p>Runs nightly, well after midnight so the day boundary is unambiguous in
 * Europe/Copenhagen and away from the invoicing and Danløn windows. The first
 * run flips roughly 291 rows — that backfill is intended.
 *
 * <p>Idempotent: an AMORTIZED row is no longer selected, so a re-run after a
 * failed deploy changes nothing. The transactional work lives in
 * {@link ItExpenseService#amortizeDueItems(LocalDate)}; this class only decides
 * when it happens, matching {@code EconomicsCustomerSyncRetryBatchlet}.
 */
@ApplicationScoped
public class ItExpenseAmortizationJob {

    private static final Logger LOG = Logger.getLogger(ItExpenseAmortizationJob.class);

    @Inject
    ItExpenseService expenseService;

    @Scheduled(cron = "0 20 3 * * ?", identity = "itbudget-amortization",
            skipExecutionIf = SchedulerShutdownGuard.class)
    void amortizeDueItems() {
        int amortized = expenseService.amortizeDueItems(LocalDate.now());
        // Logged unconditionally: a quiet night and a night the job never ran
        // look identical in CloudWatch otherwise, and "did it run?" is the first
        // question anyone asks when a budget looks wrong.
        LOG.infof("IT budget amortization: %d item(s) past their lifespan set to AMORTIZED", amortized);
    }
}
