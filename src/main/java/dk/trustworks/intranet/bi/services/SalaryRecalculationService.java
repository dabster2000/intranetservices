package dk.trustworks.intranet.bi.services;

import io.quarkus.narayana.jta.QuarkusTransaction;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.persistence.EntityManager;
import jakarta.persistence.Query;
import lombok.extern.jbosslog.JBossLog;
import org.eclipse.microprofile.context.ManagedExecutor;

import java.sql.Date;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * On-demand salary refresh for the "Your Part of Trustworks" bonus page.
 *
 * <p>Reuses {@link UserSalaryCalculatorService#recalculateSalary(String, LocalDate)} — the same
 * per-(user, day) routine the nightly user-day orchestration batch runs — to refresh the
 * {@code salary} column on existing {@code fact_user_day} rows. Only days that <em>already exist</em>
 * are touched: no new rows are created, so the {@code fact_tw_bonus_monthly} view
 * ({@code avg_salary = SUM(salary) / days_in_month}) stays internally consistent.</p>
 *
 * <p>Work runs on a {@link ManagedExecutor} worker thread (fire-and-forget) so the REST call can
 * return {@code 202 Accepted} immediately; a full fiscal year for many users is thousands of
 * upserts and can take minutes. Each unit of DB work runs in its own transaction
 * ({@code recalculateSalary} is {@code @Transactional}; the day-enumeration read uses
 * {@link QuarkusTransaction#requiringNew()}), matching the batch/batchlet precedents that do DB
 * work off the request thread.</p>
 */
@JBossLog
@ApplicationScoped
public class SalaryRecalculationService {

    @Inject
    EntityManager em;

    @Inject
    UserSalaryCalculatorService userSalaryCalculatorService;

    @Inject
    ManagedExecutor managedExecutor;

    /**
     * Single-permit guard: at most one salary recalculation job runs at a time across the app.
     * Bounds load on the shared MariaDB pool when admins double-submit or trigger overlapping
     * refreshes. Reset in the worker's {@code finally} block.
     */
    private final AtomicBoolean jobRunning = new AtomicBoolean(false);

    /**
     * Queues a background salary recalculation and returns immediately.
     *
     * @param userUuids users to refresh (already validated/non-empty by the caller)
     * @param start     inclusive window start
     * @param end       inclusive window end
     * @return {@code true} if the job was queued; {@code false} if one was already running and this
     *         submission was skipped
     */
    public boolean recalculateSalariesAsync(List<String> userUuids, LocalDate start, LocalDate end) {
        if (!jobRunning.compareAndSet(false, true)) {
            log.warnf("Salary recalculation already in progress — skipping submission: users=%d window=%s..%s",
                    userUuids.size(), start, end);
            return false;
        }
        // Defensive copy: the caller's list may be request-scoped/short-lived.
        List<String> users = List.copyOf(userUuids);
        try {
            managedExecutor.submit(() -> {
                try {
                    recalculateSalaries(users, start, end);
                } catch (Exception e) {
                    log.errorf(e, "Salary recalculation job failed: users=%d window=%s..%s",
                            users.size(), start, end);
                } finally {
                    jobRunning.set(false);
                }
            });
        } catch (RuntimeException e) {
            // Submission itself failed (e.g. executor rejection) — release the permit so the
            // guard doesn't wedge until the next restart.
            jobRunning.set(false);
            log.errorf(e, "Failed to submit salary recalculation job: users=%d window=%s..%s",
                    users.size(), start, end);
            throw e;
        }
        return true;
    }

    /**
     * Synchronous core (also unit-testable). Refreshes salary for every existing
     * {@code fact_user_day} row of each user within the window. A failure on one day is logged
     * and skipped so it does not abort the rest of the run.
     */
    void recalculateSalaries(List<String> userUuids, LocalDate start, LocalDate end) {
        long startedNs = System.nanoTime();
        long processed = 0;
        long failures = 0;

        for (String userUuid : userUuids) {
            for (LocalDate day : existingDays(userUuid, start, end)) {
                try {
                    userSalaryCalculatorService.recalculateSalary(userUuid, day);
                    processed++;
                } catch (Exception e) {
                    failures++;
                    log.errorf(e, "Salary recalc failed user=%s day=%s", userUuid, day);
                }
            }
        }

        long elapsedMs = (System.nanoTime() - startedNs) / 1_000_000;
        log.infof("Salary recalculation complete: users=%d days=%d failures=%d elapsedMs=%d window=%s..%s",
                userUuids.size(), processed, failures, elapsedMs, start, end);
    }

    /**
     * Existing {@code fact_user_day} document dates for a user within the inclusive window.
     * Runs in its own read transaction so the injected {@link EntityManager} is usable on the
     * background worker thread.
     */
    private List<LocalDate> existingDays(String userUuid, LocalDate start, LocalDate end) {
        List<?> rows = QuarkusTransaction.requiringNew().call(() -> {
            Query query = em.createNativeQuery(
                    "SELECT document_date FROM fact_user_day " +
                    "WHERE useruuid = :userUuid AND document_date BETWEEN :start AND :end " +
                    "ORDER BY document_date");
            query.setParameter("userUuid", userUuid);
            query.setParameter("start", start);
            query.setParameter("end", end);
            return query.getResultList();
        });

        List<LocalDate> days = new ArrayList<>(rows.size());
        for (Object row : rows) {
            if (row instanceof Date sqlDate) {
                days.add(sqlDate.toLocalDate());
            } else if (row instanceof LocalDate localDate) {
                days.add(localDate);
            } else if (row != null) {
                days.add(LocalDate.parse(row.toString()));
            }
        }
        return days;
    }
}
