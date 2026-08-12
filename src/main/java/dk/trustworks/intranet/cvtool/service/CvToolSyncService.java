package dk.trustworks.intranet.cvtool.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import dk.trustworks.intranet.cvtool.client.CvToolClient;
import dk.trustworks.intranet.cvtool.dto.CvToolEmployeeResponse;
import dk.trustworks.intranet.cvtool.dto.CvToolEmployeeSkinny;
import dk.trustworks.intranet.cvtool.entity.CvToolEmployeeCv;
import dk.trustworks.intranet.domain.user.entity.User;
import io.quarkus.narayana.jta.QuarkusTransaction;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import lombok.extern.jbosslog.JBossLog;
import org.eclipse.microprofile.config.inject.ConfigProperty;
import org.eclipse.microprofile.rest.client.inject.RestClient;

import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeFormatterBuilder;
import java.time.temporal.ChronoField;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * Service for syncing base CVs from the external CV Tool API into local storage.
 * <p>
 * Authentication is the {@code Ocp-Apim-Subscription-Key} header applied by
 * {@link dk.trustworks.intranet.cvtool.client.CvToolHeadersFactory} — there is
 * no login round-trip.
 * <p>
 * <b>Failures throw.</b> This job used to <em>return</em> a {@code "FAILED: ..."}
 * string on error, which JBeret recorded as {@code exitStatus: COMPLETED} and the
 * batch metrics recorded as {@code outcome: "success"} — so the sync stayed dead
 * for over a month without raising a single alert. Anything that means "no CVs
 * were synced" must propagate out of {@link #syncAllBaseCvs()} so the job is
 * marked FAILED and surfaces in nightly job monitoring.
 */
@JBossLog
@ApplicationScoped
public class CvToolSyncService {

    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();
    private static final DateTimeFormatter CV_TOOL_DATE_FORMAT =
        new DateTimeFormatterBuilder()
            .appendPattern("yyyy-MM-dd'T'HH:mm:ss")
            .appendFraction(ChronoField.MICRO_OF_SECOND, 0, 6, true)
            .toFormatter();

    @Inject
    @RestClient
    CvToolClient cvToolClient;

    @ConfigProperty(name = "cvtool.subscription-key")
    Optional<String> subscriptionKey;

    /**
     * Attempts for a single CV Tool call, including the first. Three attempts against
     * a 30s read-timeout bound one stalled call at ~96s including backoff, which is
     * nothing for a job that runs at 04:00 and normally takes ~30s.
     */
    static final int MAX_ATTEMPTS_PER_CALL = 3;

    /**
     * Retries the whole run may spend, across every call. Without this cap a gateway
     * that dies mid-run would have each of ~130 remaining employees burn its own full
     * retry ladder, stretching a failed night into hours of pointless traffic. The
     * budget is deliberately small: retrying is for the isolated blip, and anything
     * that exhausts it is an outage that should fail fast and loudly.
     */
    static final int RETRY_BUDGET_PER_RUN = 8;

    /** Swapped in tests so retry policy is exercised without real waiting. */
    CvToolRetryExecutor.Sleeper sleeper = CvToolRetryExecutor.Sleeper.REAL;

    /**
     * Main sync method. Called by the nightly batchlet.
     *
     * @return Summary string for batch job logging
     * @throws CvToolSyncException if the sync could not fetch or store any CV
     */
    public String syncAllBaseCvs() {
        log.info("Starting CV Tool sync...");

        if (subscriptionKey.map(String::trim).orElse("").isEmpty()) {
            throw new CvToolSyncException(
                "CV Tool subscription key is not configured — set CVTOOL_SUBSCRIPTION_KEY");
        }

        // One executor per run: it carries the run-wide retry budget, which must
        // reset every night. This service is @ApplicationScoped, so a field would
        // exhaust the budget once and never retry again.
        CvToolRetryExecutor retry =
                new CvToolRetryExecutor(sleeper, MAX_ATTEMPTS_PER_CALL, RETRY_BUDGET_PER_RUN);

        // Step 1: Fetch employee list.
        //
        // This single call gates every employee below it, so a transient failure here
        // costs the entire night — 0 CVs synced, not 129. It stalled to the client's
        // 30s read-timeout on 2026-08-07 and 2026-08-11 (30.18s and 30.26s, both from
        // the first call of a freshly started task) while a healthy fetch returns in
        // ~1.3s. Hence the retry: the fetch is not slow, it occasionally hangs, and a
        // second attempt against a warm gateway succeeds.
        List<CvToolEmployeeSkinny> employees;
        long startNanos = System.nanoTime();
        try {
            employees = retry.call("employee list", cvToolClient::getAllEmployees);
            log.infof("Fetched %d employees from CV Tool", employees.size());
        } catch (Exception e) {
            // The root cause goes in the message, not just the chain. As a bare
            // wrapper this read "Could not fetch employee list from CV Tool" and
            // nothing else, leaving the actual SocketTimeoutException two "Caused by:"
            // levels down a RESTEasy stack trace — present, but not what anyone sees
            // first in CloudWatch. The elapsed time is here for the same reason: it is
            // what identifies a timeout as a timeout rather than an upstream error.
            // Report the attempts actually made, not MAX_ATTEMPTS_PER_CALL. Most
            // failures stop on the first attempt because 401/403/404/500 are
            // deliberately not retried, and claiming "3 attempts in 0.4s" would both
            // be false and contradict the elapsed time beside it.
            throw new CvToolSyncException(String.format(
                    "Could not fetch employee list from CV Tool after %d attempt(s) in %.1fs — %s",
                    retry.attemptsOfLastCall(), elapsedSeconds(startNanos),
                    CvToolRetryExecutor.rootCause(e)), e);
        }

        // Step 2: Filter and sync each employee
        int synced = 0;
        int skipped = 0;
        int failed = 0;
        int unchanged = 0;

        for (CvToolEmployeeSkinny employee : employees) {
            // Skip deleted employees
            if (employee.isDeleted()) {
                skipped++;
                continue;
            }
            // Skip employees without a linked intra UUID
            if (employee.employeeUuid() == null || employee.employeeUuid().isBlank()) {
                log.debugf("Skipping employee %d (%s): no Employee_UUID", employee.id(), employee.name());
                skipped++;
                continue;
            }

            try {
                switch (syncEmployee(employee, retry)) {
                    case SYNCED -> synced++;
                    case UNCHANGED -> unchanged++;
                    case SKIPPED -> skipped++;
                }
            } catch (Exception e) {
                log.errorf(e, "Failed to sync employee %d (%s): %s",
                        employee.id(), employee.name(), CvToolRetryExecutor.rootCause(e));
                failed++;
            }
        }

        String summary = String.format(
            "CV Tool sync completed: %d synced, %d unchanged, %d skipped, %d failed (out of %d total)",
            synced, unchanged, skipped, failed, employees.size()
        );

        // Any failure fails the job. A partial failure is exactly the shape of
        // problem that hid here for a month: 8 people silently stopped syncing
        // while the run still reported success. Per-employee upserts commit in
        // their own transactions, so throwing does not discard the work that
        // did succeed — it just makes the run visible in job monitoring, and it
        // keeps being visible every night until someone fixes the cause.
        if (failed > 0) {
            log.error(summary);
            throw new CvToolSyncException(summary);
        }

        log.info(summary);
        return "COMPLETED: " + summary;
    }

    /** What a single employee's sync accomplished. Failures throw instead. */
    private enum SyncOutcome {
        /** The CV was inserted or updated. */
        SYNCED,
        /** We already hold this CV at this version — nothing to write. */
        UNCHANGED,
        /** Nothing to sync, for a reason the sync itself cannot resolve. */
        SKIPPED
    }

    /**
     * Syncs a single employee's base CV.
     *
     * @return what the sync accomplished; throws {@link CvToolSyncException} on failure
     */
    private SyncOutcome syncEmployee(CvToolEmployeeSkinny employee, CvToolRetryExecutor retry) {
        // Fetch full employee + CV data. Retried on the same terms as the list fetch:
        // any single employee failure fails the whole run (see the summary check
        // above), so one stalled socket out of ~130 is enough to turn the night red
        // even though the other 129 CVs were written. The run-wide budget stops a
        // genuine outage from spending a retry ladder per employee.
        CvToolEmployeeResponse fullEmployee =
                retry.call("employee " + employee.id(), () -> cvToolClient.getEmployee(employee.id()));

        if (fullEmployee.cv() == null || fullEmployee.cv().isNull()) {
            log.debugf("Employee %d (%s) has no CV data, skipping", employee.id(), employee.name());
            return SyncOutcome.SKIPPED;
        }

        int cvId = fullEmployee.cvId();
        if (cvId < 0) {
            log.warnf("Employee %d (%s) has CV but no valid CV ID, skipping", employee.id(), employee.name());
            return SyncOutcome.SKIPPED;
        }

        // An Employee_UUID that matches no intra user is bad data upstream, not
        // a sync fault, so it is a skip rather than a failure — otherwise one
        // stale link in the CV Tool turns the whole nightly job red until
        // someone else fixes it, and a permanently red job is how alerts stop
        // being read. Checked up front: letting it reach the INSERT instead
        // yields an opaque fk_cv_tool_employee_cv_user violation.
        //
        // Logged at WARN and counted as skipped, so the condition stays visible
        // without masking genuine failures.
        final String employeeUuidToCheck = employee.employeeUuid();
        boolean userExists = QuarkusTransaction.requiringNew().call(() ->
                User.count("uuid", employeeUuidToCheck) > 0);
        if (!userExists) {
            log.warnf("Skipping employee %d (%s): Employee_UUID %s matches no intra user — "
                            + "the CV Tool record points at a user that does not exist here",
                    employee.id(), employee.name(), employee.employeeUuid());
            return SyncOutcome.SKIPPED;
        }

        // Check if we already have this CV and if it's unchanged
        LocalDateTime cvLastUpdated = parseCvToolDateTime(fullEmployee.lastUpdatedAt());

        // Keyed on useruuid, NOT cvtoolEmployeeId. cv_tool_employee_cv has
        // UNIQUE KEY uk_useruuid — one row per person — but CV Tool can hold
        // several employee records for the same Employee_UUID (8 people do,
        // typically an old record plus a re-created one). Looking up by
        // cvtoolEmployeeId misses the existing row for the second record,
        // falls through to INSERT, and dies on the unique constraint.
        //
        // Comparing cvLastUpdatedAt then gives newest-CV-wins across those
        // duplicates: whichever record carries the newer Last_Updated_At is
        // written, and the older one is skipped as unchanged — independent of
        // the order CV Tool returns them in.
        //
        // Wrapped in its own transaction, like the upsert below. A JBeret
        // batchlet's @ActivateRequestContext is racy — the job intermittently
        // runs with no CDI request context, and an unwrapped Panache read then
        // dies with ContextNotActiveException ("neither a transaction nor a CDI
        // request context is active"). Observed here: the same code synced 127
        // employees on one run and failed all 136 on the next. Owning the
        // transaction makes it deterministic.
        final String useruuid = employee.employeeUuid();
        final LocalDateTime staleThreshold = cvLastUpdated != null ? cvLastUpdated : LocalDateTime.MIN;
        long existingCount = QuarkusTransaction.requiringNew().call(() ->
                CvToolEmployeeCv.count("useruuid = ?1 AND cvLastUpdatedAt >= ?2", useruuid, staleThreshold));
        if (cvLastUpdated != null && existingCount > 0) {
            log.debugf("Employee %d (%s) CV unchanged since last sync, skipping", employee.id(), employee.name());
            return SyncOutcome.UNCHANGED;
        }

        // Serialize the full CV JSON
        String cvJson;
        try {
            cvJson = OBJECT_MAPPER.writeValueAsString(fullEmployee.cv());
        } catch (Exception e) {
            throw new CvToolSyncException(
                "Failed to serialize CV JSON for employee " + employee.id() + " (" + employee.name() + ")", e);
        }

        // Upsert in a new transaction (so one failure doesn't roll back others)
        // Entity lookup MUST be inside requiringNew() to avoid cross-context HibernateException
        int employeeId = employee.id();
        String employeeUuid = employee.employeeUuid();
        try {
            QuarkusTransaction.requiringNew().run(() -> {
                CvToolEmployeeCv existing = CvToolEmployeeCv.find("useruuid", employeeUuid).firstResult();
                if (existing != null) {
                    // Re-point the row at whichever CV Tool record won, so the
                    // stored id matches the CV actually held.
                    existing.setCvtoolEmployeeId(employeeId);
                    existing.setCvtoolCvId(cvId);
                    existing.setEmployeeName(fullEmployee.name());
                    existing.setEmployeeTitle(fullEmployee.employeeTitle());
                    existing.setEmployeeProfile(fullEmployee.employeeProfile());
                    existing.setCvDataJson(cvJson);
                    existing.setCvLanguage(fullEmployee.cvLanguage());
                    existing.setLastSyncedAt(LocalDateTime.now());
                    existing.setCvLastUpdatedAt(cvLastUpdated);
                    existing.persist();
                } else {
                    CvToolEmployeeCv newRecord = new CvToolEmployeeCv();
                    newRecord.setUuid(UUID.randomUUID().toString());
                    newRecord.setUseruuid(employeeUuid);
                    newRecord.setCvtoolEmployeeId(employeeId);
                    newRecord.setCvtoolCvId(cvId);
                    newRecord.setEmployeeName(fullEmployee.name());
                    newRecord.setEmployeeTitle(fullEmployee.employeeTitle());
                    newRecord.setEmployeeProfile(fullEmployee.employeeProfile());
                    newRecord.setCvDataJson(cvJson);
                    newRecord.setCvLanguage(fullEmployee.cvLanguage());
                    newRecord.setLastSyncedAt(LocalDateTime.now());
                    newRecord.setCvLastUpdatedAt(cvLastUpdated);
                    newRecord.persist();
                }
            });
        } catch (Exception e) {
            // Must throw, not return false: false means "unchanged", so a
            // persist failure used to be counted as a success. That is how a
            // run reported "0 failed" while 9 employees had in fact failed.
            throw new CvToolSyncException(
                "Failed to persist CV for employee " + employee.id() + " (" + employee.name() + ")", e);
        }

        log.debugf("Synced CV for employee %d (%s)", employee.id(), fullEmployee.name());
        return SyncOutcome.SYNCED;
    }

    private static double elapsedSeconds(long startNanos) {
        return (System.nanoTime() - startNanos) / 1_000_000_000.0;
    }

    /**
     * Parses the CV Tool datetime format (e.g. "2025-11-27T14:04:19.874353").
     */
    private LocalDateTime parseCvToolDateTime(String dateTimeStr) {
        if (dateTimeStr == null || dateTimeStr.isBlank()) return null;
        try {
            return LocalDateTime.parse(dateTimeStr, CV_TOOL_DATE_FORMAT);
        } catch (Exception e) {
            log.debugf("Could not parse CV Tool datetime '%s': %s", dateTimeStr, e.getMessage());
            return null;
        }
    }

    /** Signals that the CV Tool sync could not complete — fails the batch job. */
    public static class CvToolSyncException extends RuntimeException {
        public CvToolSyncException(String message) {
            super(message);
        }

        public CvToolSyncException(String message, Throwable cause) {
            super(message, cause);
        }
    }
}
