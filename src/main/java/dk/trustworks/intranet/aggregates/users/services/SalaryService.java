package dk.trustworks.intranet.aggregates.users.services;

import dk.trustworks.intranet.aggregates.sender.AggregateEventSender;
import dk.trustworks.intranet.aggregates.users.events.CreateSalaryLogEvent;
import dk.trustworks.intranet.aggregates.users.events.DeleteSalaryEvent;
import dk.trustworks.intranet.aggregates.users.events.UpdateSalaryEvent;
import dk.trustworks.intranet.domain.user.entity.Salary;
import dk.trustworks.intranet.domain.user.entity.UserDanlonHistory;
import dk.trustworks.intranet.domain.user.entity.UserStatus;
import dk.trustworks.intranet.userservice.model.enums.SalaryType;
import dk.trustworks.intranet.userservice.model.enums.StatusType;
import io.quarkus.cache.CacheInvalidateAll;
import io.quarkus.narayana.jta.QuarkusTransaction;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.transaction.Transactional;
import jakarta.validation.Valid;
import lombok.extern.jbosslog.JBossLog;

import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

@JBossLog
@ApplicationScoped
public class SalaryService {

    @Inject
    AggregateEventSender aggregateEventSender;

    @Inject
    dk.trustworks.intranet.aggregates.users.danlon.DanlonEventDetector danlonEventDetector;

    @Inject
    dk.trustworks.intranet.aggregates.users.danlon.DanlonAssignmentService danlonAssignmentService;

    public List<Salary> listAll(String useruuid) {
        return Salary.findByUseruuid(useruuid);
    }

    public Salary getUserSalaryByMonth(String useruuid, LocalDate month) {
        return Salary.<Salary>find("useruuid = ?1 and activefrom <= ?2 order by activefrom desc",
                useruuid, month).firstResultOptional().orElse(new Salary(month, 0, useruuid));
    }

    public boolean isSalaryChanged(String useruuid, LocalDate month) {
        return Salary.<Salary>find("activefrom = ?1 and useruuid = ?2", month, useruuid).firstResultOptional().isPresent();
    }

    @Transactional
    @CacheInvalidateAll(cacheName = "user-cache")
    public void create(@Valid Salary salary) {
        if (salary.getUuid() == null || salary.getUuid().isBlank()) {
            String generatedUuid = UUID.randomUUID().toString();
            log.infof("No salary UUID provided for user %s, generating new one: %s", salary.getUseruuid(), generatedUuid);
            salary.setUuid(generatedUuid);
        }
        Optional<Salary> existingSalary = Salary.findByIdOptional(salary.getUuid());
        existingSalary.ifPresentOrElse(s -> {
            // UPDATE EXISTING SALARY RECORD
            log.debugf("Updating existing salary record for user %s (UUID: %s)", s.getUseruuid(), s.getUuid());

            // Capture old salary type before updating
            SalaryType oldType = s.getType();

            // Update salary fields
            s.setSalary(salary.getSalary());
            s.setLunch(salary.isLunch());
            s.setPhone(salary.isPhone());
            s.setPrayerDay(salary.isPrayerDay());
            s.setType(salary.getType());
            s.setActivefrom(salary.getActivefrom());
            updateSalary(s);

            // NEW BUSINESS RULE: Check for HOURLY → NORMAL transition
            SalaryType newType = salary.getType();
            if (oldType == SalaryType.HOURLY && newType == SalaryType.NORMAL) {
                log.infof("Detected salary type change HOURLY → NORMAL for user %s, effective %s (UPDATE path)",
                        s.getUseruuid(), salary.getActivefrom());
                handleSalaryTypeChange(s.getUseruuid(), salary.getActivefrom());
            }

            aggregateEventSender.handleEvent(new UpdateSalaryEvent(s.getUseruuid(), s));
        }, () -> {
            // CREATE NEW SALARY RECORD
            log.infof("Creating new salary record for user %s (UUID: %s, effective: %s, type: %s)",
                    salary.getUseruuid(), salary.getUuid(), salary.getActivefrom(), salary.getType());

            // persistAndFlush so a constraint violation surfaces to the caller HERE, not later
            // inside handleSalaryTypeChange's best-effort try/catch (which would swallow the
            // business-save failure → false success). See StatusService.create for the rationale.
            salary.persistAndFlush();
            aggregateEventSender.handleEvent(new CreateSalaryLogEvent(salary.getUseruuid(), salary));

            // FIX: Check for salary type change even when creating new record
            // This handles the case where a new NORMAL salary record is created instead of updating existing HOURLY record
            if (salary.getType() == SalaryType.NORMAL) {
                log.debugf("New salary is NORMAL type - checking if previous was HOURLY for user %s", salary.getUseruuid());

                // Look up previous salary for this user (month before new salary's effective date)
                Salary previousSalary = getUserSalaryByMonth(salary.getUseruuid(), salary.getActivefrom().minusMonths(1));

                if (previousSalary != null && previousSalary.getType() == SalaryType.HOURLY) {
                    log.infof("Detected salary type change HOURLY → NORMAL for user %s, effective %s (CREATE path, previous: %s)",
                            salary.getUseruuid(), salary.getActivefrom(), previousSalary.getActivefrom());
                    handleSalaryTypeChange(salary.getUseruuid(), salary.getActivefrom());
                } else {
                    log.debugf("Previous salary for user %s was not HOURLY (was: %s) - no Danløn generation",
                            salary.getUseruuid(), previousSalary != null ? previousSalary.getType() : "null");
                }
            }
        });
    }

    private void updateSalary(Salary salary) {
        log.debugf("Updating salary uuid=%s for user %s", salary.getUuid(), salary.getUseruuid());
        Salary.update("salary = ?1, " +
                        "activefrom = ?2, " +
                        "type = ?3, " +
                        "lunch = ?4, " +
                        "phone = ?5, " +
                        "prayerDay = ?6 " +
                        "WHERE uuid LIKE ?7 ",
                salary.getSalary(),
                salary.getActivefrom(),
                salary.getType(),
                salary.isLunch(),
                salary.isPhone(),
                salary.isPrayerDay(),
                salary.getUuid());
    }

    @Transactional
    @CacheInvalidateAll(cacheName = "user-cache")
    public void delete(String salaryuuid) {
        Optional<Salary> optionalSalary = Salary.findByIdOptional(salaryuuid);

        // Forward-only Danløn (spec §7): on deleting the NORMAL salary that backed a mint, raise a
        // CLOSE proposal for that month's OPEN system-minted row instead of hard-deleting it.
        optionalSalary.ifPresent(salary -> {
            if (salary.getType() == SalaryType.NORMAL) {
                LocalDate month = salary.getActivefrom().withDayOfMonth(1);
                UserDanlonHistory row = UserDanlonHistory.findRowForMonth(salary.getUseruuid(), month);
                if (row != null && !row.isClosed() && row.getEventType() != null) {
                    danlonAssignmentService.proposeClose(row.getUuid(),
                            "Triggering NORMAL salary for " + month + " was deleted");
                }
            }
        });

        Salary.deleteById(salaryuuid);
        optionalSalary.ifPresent(salary ->
                aggregateEventSender.handleEvent(new DeleteSalaryEvent(salary.getUseruuid(), salaryuuid)));
    }

    public List<Salary> findByUseruuid(String useruuid) {
        return Salary.findByUseruuid(useruuid);
    }

    /**
     * Handle salary type change from HOURLY to NORMAL (monthly).
     * <p>
     * This method implements the business rule for automatic Danløn number generation:
     * - Check if user has a new UserStatus in the same month
     * - UserStatus must NOT be TERMINATED or PREBOARDING
     * - If conditions met, generate new Danløn number and create history record
     * </p>
     * <p>
     * <b>Design Note:</b> This is triggered from SalaryService.create() when salary type changes.
     * This ensures all salary changes (via REST API, batch jobs, migrations) are handled consistently.
     * </p>
     *
     * @param useruuid      User UUID
     * @param effectiveDate Date when salary type change becomes effective
     */
    /**
     * HOURLY → NORMAL transition (spec §6): delegate to the shared detector so the same
     * precedence/window applies whether the status or the salary write triggers it, and raise
     * at most one proposal — minting nothing (AC1). Never rolls back the salary save (N5):
     * detection runs in the caller's transaction (so the just-written NORMAL salary is visible),
     * and only the proposal write is isolated in a nested transaction; the reconciliation scan
     * (AC10) re-derives and re-raises if the write fails.
     */
    private void handleSalaryTypeChange(String useruuid, LocalDate effectiveDate) {
        LocalDate month = effectiveDate.withDayOfMonth(1);
        UserStatus status = UserStatus.<UserStatus>find(
                "useruuid = ?1 and statusdate <= ?2 and status not in (?3, ?4) order by statusdate desc",
                useruuid, month.plusMonths(1).minusDays(1), StatusType.TERMINATED, StatusType.PREBOARDING)
                .firstResult();
        if (status == null || status.getCompany() == null) {
            log.infof("No qualifying status/company for user %s month %s — no Danløn proposal raised", useruuid, month);
            return;
        }
        String companyUuid = status.getCompany().getUuid();
        // Best-effort: detect in this (caller) transaction so the just-written NORMAL salary is
        // visible, isolate the WRITE in requiringNew, and wrap the whole thing so neither a
        // detection-read glitch nor a propose failure rolls back the salary save (N5). Reconciliation
        // (AC10) re-derives and re-raises on its next run.
        try {
            var event = danlonEventDetector.detectMostSpecific(useruuid, month, companyUuid);
            if (event.isEmpty()) return;
            QuarkusTransaction.requiringNew().run(() ->
                    danlonAssignmentService.proposeIfNeeded(useruuid, month, event.get(), companyUuid));
        } catch (RuntimeException e) {
            log.warnf(e, "Danløn detect/propose failed for user %s month %s — salary save best-effort; reconciliation (AC10) will retry",
                    useruuid, month);
        }
    }
}