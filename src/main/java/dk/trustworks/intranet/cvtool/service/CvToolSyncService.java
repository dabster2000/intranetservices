package dk.trustworks.intranet.cvtool.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import dk.trustworks.intranet.cvtool.client.CvToolAuthClient;
import dk.trustworks.intranet.cvtool.client.CvToolClient;
import dk.trustworks.intranet.cvtool.dto.CvToolEmployeeResponse;
import dk.trustworks.intranet.cvtool.dto.CvToolEmployeeSkinny;
import dk.trustworks.intranet.cvtool.dto.CvToolTokenResponse;
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
import java.util.UUID;

/**
 * Service for syncing base CVs from the external CV Tool API into local storage.
 * <p>
 * Authentication: Uses cookie-based JWT auth (programmatic login).
 * When header-based auth becomes available, swap the auth logic here.
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
    CvToolAuthClient authClient;

    @Inject
    @RestClient
    CvToolClient cvToolClient;

    @ConfigProperty(name = "cvtool.username")
    String username;

    @ConfigProperty(name = "cvtool.password")
    String password;

    /**
     * Main sync method. Called by the nightly batchlet.
     *
     * @return Summary string for batch job logging
     */
    public String syncAllBaseCvs() {
        log.info("Starting CV Tool sync...");

        // Step 1: Authenticate
        String cookieHeader = authenticate();
        if (cookieHeader == null) {
            return "FAILED: Authentication failed";
        }

        // Step 2: Fetch employee list
        List<CvToolEmployeeSkinny> employees;
        try {
            employees = cvToolClient.getAllEmployees(cookieHeader);
            log.infof("Fetched %d employees from CV Tool", employees.size());
        } catch (Exception e) {
            log.errorf(e, "Failed to fetch employee list from CV Tool");
            return "FAILED: Could not fetch employee list - " + e.getMessage();
        }

        // Step 3: Filter and sync each employee
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
                boolean updated = syncEmployee(employee, cookieHeader);
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
        log.info(summary);
        return "COMPLETED: " + summary;
    }

    /**
     * Authenticates with the CV Tool API and returns the Cookie header string.
     */
    private String authenticate() {
        try {
            log.info("Authenticating with CV Tool API...");
            CvToolTokenResponse tokenResponse = authClient.login(username, password);

            if (!tokenResponse.success() || tokenResponse.token() == null) {
                log.errorf("CV Tool authentication failed: %s", tokenResponse.failureReason());
                return null;
            }

            log.info("CV Tool authentication successful");
            return "jwt_authorization=" + tokenResponse.token();
        } catch (Exception e) {
            log.errorf(e, "CV Tool authentication error");
            return null;
        }
    }

    /**
     * Syncs a single employee's base CV.
     *
     * @return true if data was inserted/updated, false if unchanged
     */
    private boolean syncEmployee(CvToolEmployeeSkinny employee, String cookieHeader) {
        // Fetch full employee + CV data
        CvToolEmployeeResponse fullEmployee = cvToolClient.getEmployee(employee.id(), cookieHeader);

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
        CvToolEmployeeCv existing = CvToolEmployeeCv.find("cvtoolEmployeeId", employee.id()).firstResult();

        if (existing != null && existing.getCvLastUpdatedAt() != null && cvLastUpdated != null
            && !cvLastUpdated.isAfter(existing.getCvLastUpdatedAt())) {
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
        try {
            QuarkusTransaction.requiringNew().run(() -> {
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
                    newRecord.setUseruuid(employee.employeeUuid());
                    newRecord.setCvtoolEmployeeId(employee.id());
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
}
