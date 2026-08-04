package dk.trustworks.intranet.cvtool.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import dk.trustworks.intranet.cvtool.client.CvToolClient;
import dk.trustworks.intranet.cvtool.dto.CvToolEmployeeResponse;
import dk.trustworks.intranet.cvtool.dto.CvToolEmployeeSkinny;
import dk.trustworks.intranet.cvtool.entity.CvToolEmployeeCv;
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

        // Step 1: Fetch employee list
        List<CvToolEmployeeSkinny> employees;
        try {
            employees = cvToolClient.getAllEmployees();
            log.infof("Fetched %d employees from CV Tool", employees.size());
        } catch (Exception e) {
            throw new CvToolSyncException("Could not fetch employee list from CV Tool", e);
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
                boolean updated = syncEmployee(employee);
                if (updated) {
                    synced++;
                } else {
                    unchanged++;
                }
            } catch (Exception e) {
                log.errorf(e, "Failed to sync employee %d (%s)", employee.id(), employee.name());
                failed++;
            }
        }

        String summary = String.format(
            "CV Tool sync completed: %d synced, %d unchanged, %d skipped, %d failed (out of %d total)",
            synced, unchanged, skipped, failed, employees.size()
        );

        // Every candidate failed — the run accomplished nothing. Never report
        // that as a success.
        if (failed > 0 && synced == 0 && unchanged == 0) {
            throw new CvToolSyncException("All " + failed + " CV Tool employees failed to sync");
        }

        log.info(summary);
        return "COMPLETED: " + summary;
    }

    /**
     * Syncs a single employee's base CV.
     *
     * @return true if data was inserted/updated, false if unchanged
     */
    private boolean syncEmployee(CvToolEmployeeSkinny employee) {
        // Fetch full employee + CV data
        CvToolEmployeeResponse fullEmployee = cvToolClient.getEmployee(employee.id());

        if (fullEmployee.cv() == null || fullEmployee.cv().isNull()) {
            log.debugf("Employee %d (%s) has no CV data, skipping", employee.id(), employee.name());
            return false;
        }

        int cvId = fullEmployee.cvId();
        if (cvId < 0) {
            log.warnf("Employee %d (%s) has CV but no valid CV ID, skipping", employee.id(), employee.name());
            return false;
        }

        // Check if we already have this CV and if it's unchanged
        LocalDateTime cvLastUpdated = parseCvToolDateTime(fullEmployee.lastUpdatedAt());

        // Check staleness outside the transaction (read-only, no entity managed state needed)
        long existingCount = CvToolEmployeeCv.count("cvtoolEmployeeId = ?1 AND cvLastUpdatedAt >= ?2",
                employee.id(), cvLastUpdated != null ? cvLastUpdated : LocalDateTime.MIN);
        if (cvLastUpdated != null && existingCount > 0) {
            log.debugf("Employee %d (%s) CV unchanged since last sync, skipping", employee.id(), employee.name());
            return false;
        }

        // Serialize the full CV JSON
        String cvJson;
        try {
            cvJson = OBJECT_MAPPER.writeValueAsString(fullEmployee.cv());
        } catch (Exception e) {
            log.errorf(e, "Failed to serialize CV JSON for employee %d", employee.id());
            return false;
        }

        // Upsert in a new transaction (so one failure doesn't roll back others)
        // Entity lookup MUST be inside requiringNew() to avoid cross-context HibernateException
        int employeeId = employee.id();
        String employeeUuid = employee.employeeUuid();
        try {
            QuarkusTransaction.requiringNew().run(() -> {
                CvToolEmployeeCv existing = CvToolEmployeeCv.find("cvtoolEmployeeId", employeeId).firstResult();
                if (existing != null) {
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
            log.errorf(e, "Failed to persist CV for employee %d (%s)", employee.id(), employee.name());
            return false;
        }

        log.debugf("Synced CV for employee %d (%s)", employee.id(), fullEmployee.name());
        return true;
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
