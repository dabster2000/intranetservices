package dk.trustworks.intranet.aggregates.users.services;

import dk.trustworks.intranet.aggregates.sender.AggregateEventSender;
import dk.trustworks.intranet.aggregates.users.events.CreateSalaryLogEvent;
import dk.trustworks.intranet.aggregates.users.events.DeleteSalaryEvent;
import dk.trustworks.intranet.aggregates.users.events.UpdateSalaryEvent;
import dk.trustworks.intranet.domain.user.entity.Salary;
import dk.trustworks.intranet.domain.user.entity.UserStatus;
import dk.trustworks.intranet.domain.user.service.UserDanlonHistoryService;
import dk.trustworks.intranet.userservice.model.enums.SalaryType;
import dk.trustworks.intranet.userservice.model.enums.StatusType;
import io.quarkus.cache.CacheInvalidateAll;
import io.quarkus.cache.CacheResult;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.persistence.NoResultException;
import jakarta.transaction.Transactional;
import jakarta.validation.Valid;
import lombok.extern.jbosslog.JBossLog;

import java.time.LocalDate;
import java.util.List;
import java.util.Optional;

@JBossLog
@ApplicationScoped
public class SalaryService {

    @Inject
    AggregateEventSender aggregateEventSender;

    @Inject
    UserDanlonHistoryService danlonHistoryService;

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
        if(salary.getUuid().isEmpty()) return;
        Optional<Salary> existingSalary = Salary.findByIdOptional(salary.getUuid());
        existingSalary.ifPresentOrElse(s -> {
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
                log.infof("Detected salary type change HOURLY → NORMAL for user %s, effective %s",
                        s.getUseruuid(), salary.getActivefrom());
                handleSalaryTypeChange(s.getUseruuid(), salary.getActivefrom());
            }

            aggregateEventSender.handleEvent(new UpdateSalaryEvent(s.getUseruuid(), s));
        }, () -> {
            salary.persist();
            aggregateEventSender.handleEvent(new CreateSalaryLogEvent(salary.getUseruuid(), salary));
        });
    }

    private void updateSalary(Salary salary) {
        log.info("Updating salary: " + salary);
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
        Salary.deleteById(salaryuuid);
        optionalSalary.ifPresent(salary -> aggregateEventSender.handleEvent(new DeleteSalaryEvent(salary.getUseruuid(), salaryuuid)));
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
    private void handleSalaryTypeChange(String useruuid, LocalDate effectiveDate) {
        LocalDate monthStart = effectiveDate.withDayOfMonth(1);
        LocalDate monthEnd = monthStart.plusMonths(1);

        // Check if user has new UserStatus in same month (excluding TERMINATED/PREBOARDING)
        Optional<UserStatus> newStatus = UserStatus.find(
                "useruuid = ?1 AND statusdate >= ?2 AND statusdate < ?3 AND status NOT IN (?4, ?5)",
                useruuid,
                monthStart,
                monthEnd,
                StatusType.TERMINATED,
                StatusType.PREBOARDING
        ).firstResultOptional();

        if (newStatus.isEmpty()) {
            log.infof("No qualifying UserStatus found for user %s in month %s - skipping Danløn generation",
                    useruuid, monthStart);
            return;
        }

        log.infof("Found qualifying UserStatus (%s) for user %s in month %s - generating new Danløn number",
                newStatus.get().getStatus(), useruuid, monthStart);

        // Generate new Danløn number using the service
        String newDanlonNumber = danlonHistoryService.generateNextDanlonNumber();

        // Create UserDanlonHistory record
        try {
            danlonHistoryService.addDanlonHistory(
                    useruuid,
                    monthStart, // Active from 1st of month
                    newDanlonNumber,
                    "system-salary-type-change"
            );
            log.infof("Created new Danløn number %s for user %s due to HOURLY → NORMAL transition",
                    newDanlonNumber, useruuid);
        } catch (IllegalArgumentException e) {
            // Duplicate history for this month - log and continue
            // This can happen if salary type changes multiple times in same month
            log.warnf("Failed to create Danløn history for user %s: %s", useruuid, e.getMessage());
        }
    }
}