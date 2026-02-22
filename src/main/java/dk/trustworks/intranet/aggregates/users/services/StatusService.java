package dk.trustworks.intranet.aggregates.users.services;

import dk.trustworks.intranet.aggregates.sender.AggregateEventSender;
import dk.trustworks.intranet.aggregates.users.events.CreateUserStatusEvent;
import dk.trustworks.intranet.aggregates.users.events.DeleteUserStatusEvent;
import dk.trustworks.intranet.aggregates.users.events.UpdateUserStatusEvent;
import dk.trustworks.intranet.domain.user.entity.Salary;
import dk.trustworks.intranet.domain.user.entity.UserDanlonHistory;
import dk.trustworks.intranet.domain.user.entity.UserStatus;
import dk.trustworks.intranet.domain.user.service.UserDanlonHistoryService;
import dk.trustworks.intranet.userservice.model.enums.SalaryType;
import dk.trustworks.intranet.userservice.model.enums.StatusType;
import io.quarkus.cache.CacheInvalidateAll;
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
public class StatusService {

    @Inject
    AggregateEventSender aggregateEventSender;

    @Inject
    SalaryService salaryService;

    @Inject
    UserDanlonHistoryService danlonHistoryService;

    public List<UserStatus> listAll(String useruuid) {
        return UserStatus.findByUseruuid(useruuid);
    }

    public UserStatus getFirstEmploymentStatus(String useruuid) {
        return UserStatus.find("useruuid like ?1 order by statusdate asc limit 1", useruuid).firstResult();
    }

    public UserStatus getLatestEmploymentStatus(String useruuid) {
        List<UserStatus> userStatusList = UserStatus.find("useruuid like ?1 and statusdate < ?2 order by statusdate desc", useruuid, LocalDate.now()).list();
        UserStatus latestEmployed = null;
        for (UserStatus userStatus : userStatusList) {
            if(userStatus.getStatus().equals(StatusType.TERMINATED)) return latestEmployed;
            latestEmployed = userStatus;
        }
        return latestEmployed;
    }

    @Transactional
    @CacheInvalidateAll(cacheName = "user-cache")
    @CacheInvalidateAll(cacheName = "user-status-cache")
    @CacheInvalidateAll(cacheName = "employee-availability")
    public void create(@Valid UserStatus status) {
        if(status.getUuid() == null || status.getUuid().isEmpty()) {
            status.uuid = UUID.randomUUID().toString();
        }
        Optional<UserStatus> existingStatus = UserStatus.findByIdOptional(status.getUuid());
        existingStatus.ifPresentOrElse(s -> {
            log.info("StatusService.create -> updating status");
            log.info("status = " + status);

            // VALIDATION: Prevent updates that would orphan Danløn numbers
            validateStatusUpdate(s, status);

            s.setStatus(status.getStatus());
            s.setStatusdate(status.getStatusdate());
            s.setType(status.getType());
            s.setCompany(status.getCompany());
            s.setTwBonusEligible(status.isTwBonusEligible());
            s.setAllocation(status.getAllocation());
            updateStatusType(s);
            sendUpdateEvent(s);

            // BUSINESS RULE 1: Check for company transition (TERMINATED in one company, ACTIVE in another)
            // This check takes precedence over salary type change and re-employment
            checkForCompanyTransition(s);

            // BUSINESS RULE 2: Check for pending salary type change (HOURLY → NORMAL)
            // Only check if company transition didn't generate Danløn number
            if (!danlonHistoryService.hasDanlonChangedInMonthBy(s.getUseruuid(),
                    s.getStatusdate().withDayOfMonth(1), "system-company-transition")) {
                checkForPendingSalaryTypeChange(s);
            }

            // BUSINESS RULE 3: Check for re-employment (TERMINATED → ACTIVE)
            // Only check if no other rule has generated Danløn number this month
            if (!danlonHistoryService.hasDanlonChangedInMonth(s.getUseruuid(),
                    s.getStatusdate().withDayOfMonth(1))) {
                checkForReEmployment(s);
            }
        }, () -> {
            log.info("StatusService.create -> creating status");
            log.info("status = " + status);
            status.persist();
            sendCreateEvent(status);

            // BUSINESS RULE 1: Check for company transition (TERMINATED in one company, ACTIVE in another)
            // This check takes precedence over salary type change and re-employment
            checkForCompanyTransition(status);

            // BUSINESS RULE 2: Check for pending salary type change (HOURLY → NORMAL)
            // Only check if company transition didn't generate Danløn number
            if (!danlonHistoryService.hasDanlonChangedInMonthBy(status.getUseruuid(),
                    status.getStatusdate().withDayOfMonth(1), "system-company-transition")) {
                checkForPendingSalaryTypeChange(status);
            }

            // BUSINESS RULE 3: Check for re-employment (TERMINATED → ACTIVE)
            // Only check if no other rule has generated Danløn number this month
            if (!danlonHistoryService.hasDanlonChangedInMonth(status.getUseruuid(),
                    status.getStatusdate().withDayOfMonth(1))) {
                checkForReEmployment(status);
            }
        });
    }

    private void updateStatusType(UserStatus s) {
        UserStatus.update("status = ?1, " +
                        "statusdate = ?2, " +
                        "type = ?3, " +
                        "company = ?4, " +
                        "isTwBonusEligible = ?5, " +
                        "allocation = ?6 " +
                        "WHERE uuid LIKE ?7 ",
                s.getStatus(),
                s.getStatusdate(),
                s.getType(),
                s.getCompany(),
                s.isTwBonusEligible(),
                s.getAllocation(),
                s.getUuid());
    }

    @Transactional
    @CacheInvalidateAll(cacheName = "user-cache")
    @CacheInvalidateAll(cacheName = "user-status-cache")
    @CacheInvalidateAll(cacheName = "employee-availability")
    public void delete(String statusuuid) {
        log.info("StatusService.delete");
        log.info("statusuuid = " + statusuuid);
        UserStatus entity = UserStatus.<UserStatus>findById(statusuuid);

        // CLEANUP RULE 1: Company transition Danløn
        // Delete company transition Danløn if EITHER TERMINATED or ACTIVE status is deleted
        // Company transition requires BOTH statuses (TERMINATED in one company, ACTIVE in another on same date)
        // Deleting either status invalidates the transition, so Danløn must be removed
        if (entity != null && (entity.getStatus() == StatusType.TERMINATED || entity.getStatus() == StatusType.ACTIVE)) {
            LocalDate monthStart = entity.getStatusdate().withDayOfMonth(1);
            String useruuid = entity.getUseruuid();

            log.infof("Deleting %s status for user %s on date %s - checking for company transition Danløn cleanup",
                    entity.getStatus(), useruuid, entity.getStatusdate());

            // Find company transition Danløn history for this month
            Optional<UserDanlonHistory> companyTransitionDanlon = UserDanlonHistory.find(
                    "useruuid = ?1 AND activeDate = ?2 AND createdBy = ?3",
                    useruuid,
                    monthStart,
                    "system-company-transition"
            ).firstResultOptional();

            if (companyTransitionDanlon.isPresent()) {
                UserDanlonHistory danlonToDelete = (UserDanlonHistory) companyTransitionDanlon.get();
                log.warnf("Deleting company transition Danløn history for user %s: uuid=%s, danlon=%s, activeDate=%s (triggered by %s status deletion)",
                        useruuid, danlonToDelete.getUuid(), danlonToDelete.getDanlon(), danlonToDelete.getActiveDate(), entity.getStatus());

                try {
                    // Use the service method to ensure denormalized field is updated correctly
                    danlonHistoryService.deleteDanlonHistory(danlonToDelete.getUuid());
                    log.infof("Successfully deleted company transition Danløn history for user %s", useruuid);
                } catch (Exception e) {
                    // Log error but don't fail the status deletion
                    log.errorf(e, "Failed to delete company transition Danløn history for user %s", useruuid);
                }
            } else {
                log.debugf("No company transition Danløn history found for user %s in month %s - skipping cleanup",
                        useruuid, monthStart);
            }
        }

        // CLEANUP RULE 2: If deleting an ACTIVE status, clean up re-employment Danløn history
        // Only delete Danløn history with marker 'system-re-employment' to preserve other markers
        // (salary type change, manual entries)
        if (entity != null && entity.getStatus() == StatusType.ACTIVE) {
            LocalDate monthStart = entity.getStatusdate().withDayOfMonth(1);
            String useruuid = entity.getUseruuid();

            log.infof("Deleting ACTIVE status for user %s on date %s - checking for re-employment Danløn cleanup",
                    useruuid, entity.getStatusdate());

            // Find re-employment Danløn history for this month
            Optional<UserDanlonHistory> reEmploymentDanlon = UserDanlonHistory.find(
                    "useruuid = ?1 AND activeDate = ?2 AND createdBy = ?3",
                    useruuid,
                    monthStart,
                    "system-re-employment"
            ).firstResultOptional();

            if (reEmploymentDanlon.isPresent()) {
                UserDanlonHistory danlonToDelete = (UserDanlonHistory) reEmploymentDanlon.get();
                log.warnf("Deleting re-employment Danløn history for user %s: uuid=%s, danlon=%s, activeDate=%s",
                        useruuid, danlonToDelete.getUuid(), danlonToDelete.getDanlon(), danlonToDelete.getActiveDate());

                try {
                    // Use the service method to ensure denormalized field is updated correctly
                    danlonHistoryService.deleteDanlonHistory(danlonToDelete.getUuid());
                    log.infof("Successfully deleted re-employment Danløn history for user %s", useruuid);
                } catch (Exception e) {
                    // Log error but don't fail the status deletion
                    log.errorf(e, "Failed to delete re-employment Danløn history for user %s", useruuid);
                }
            } else {
                log.debugf("No re-employment Danløn history found for user %s in month %s - skipping cleanup",
                        useruuid, monthStart);
            }

            // CLEANUP RULE 3: Also clean up salary type change Danløn
            // This handles the case where deleting the UserStatus that enabled the salary type change Danløn
            Optional<UserDanlonHistory> salaryChangeDanlon = UserDanlonHistory.find(
                    "useruuid = ?1 AND activeDate = ?2 AND createdBy = ?3",
                    useruuid,
                    monthStart,
                    "system-salary-type-change"
            ).firstResultOptional();

            if (salaryChangeDanlon.isPresent()) {
                UserDanlonHistory danlonToDelete = (UserDanlonHistory) salaryChangeDanlon.get();
                log.warnf("Deleting salary-type-change Danløn history for user %s: uuid=%s, danlon=%s, activeDate=%s",
                        useruuid, danlonToDelete.getUuid(), danlonToDelete.getDanlon(), danlonToDelete.getActiveDate());

                try {
                    danlonHistoryService.deleteDanlonHistory(danlonToDelete.getUuid());
                    log.infof("Successfully deleted salary-type-change Danløn history for user %s", useruuid);
                } catch (Exception e) {
                    log.errorf(e, "Failed to delete salary-type-change Danløn history for user %s", useruuid);
                }
            } else {
                log.debugf("No salary-type-change Danløn history found for user %s in month %s - skipping cleanup",
                        useruuid, monthStart);
            }
        }

        UserStatus.deleteById(statusuuid);
        DeleteUserStatusEvent event = new DeleteUserStatusEvent(entity.getUseruuid(), entity);
        aggregateEventSender.handleEvent(event);
    }

    private void sendCreateEvent(UserStatus status) {
        CreateUserStatusEvent event = new CreateUserStatusEvent(status.getUseruuid(), status);
        aggregateEventSender.handleEvent(event);
    }

    private void sendUpdateEvent(UserStatus status) {
        UpdateUserStatusEvent event = new UpdateUserStatusEvent(status.getUseruuid(), status);
        aggregateEventSender.handleEvent(event);
    }

    /**
     * Validates that a UserStatus update will not orphan Danløn numbers.
     * <p>
     * This method enforces strict validation to prevent updates that would create orphaned Danløn history records.
     * Users must DELETE the old status and CREATE a new one instead of UPDATE when:
     * 1. Changing status type from ACTIVE to something else (would orphan re-employment/company transition Danløn)
     * 2. Changing statusdate to a different month (would orphan Danløn in old month)
     * </p>
     *
     * @param existingStatus The current UserStatus in the database
     * @param newStatus The updated UserStatus being saved
     * @throws IllegalStateException if the update would orphan Danløn numbers
     */
    private void validateStatusUpdate(UserStatus existingStatus, UserStatus newStatus) {
        String useruuid = existingStatus.getUseruuid();
        LocalDate oldMonth = existingStatus.getStatusdate().withDayOfMonth(1);
        LocalDate newMonth = newStatus.getStatusdate().withDayOfMonth(1);

        // VALIDATION 1: Prevent status type changes from ACTIVE if Danløn exists
        boolean statusChangingFromActive = existingStatus.getStatus() == StatusType.ACTIVE
                && newStatus.getStatus() != StatusType.ACTIVE;

        if (statusChangingFromActive) {
            // Check if there's a Danløn number for this user in this month
            boolean hasDanlonThisMonth = danlonHistoryService.hasDanlonChangedInMonth(useruuid, oldMonth);

            if (hasDanlonThisMonth) {
                throw new IllegalStateException(
                    "Cannot change status from ACTIVE to " + newStatus.getStatus() +
                    " because it would orphan Danløn number for " + oldMonth.getMonth() + " " + oldMonth.getYear() + ". " +
                    "Please DELETE this status and CREATE a new " + newStatus.getStatus() + " status instead."
                );
            }
        }

        // VALIDATION 2: Prevent date changes to different month if Danløn exists in old month
        boolean dateChangingToNewMonth = !oldMonth.equals(newMonth);

        if (dateChangingToNewMonth) {
            // Check if there's a Danløn number for the OLD month
            boolean hasDanlonInOldMonth = danlonHistoryService.hasDanlonChangedInMonth(useruuid, oldMonth);

            if (hasDanlonInOldMonth) {
                throw new IllegalStateException(
                    "Cannot change status date from " + existingStatus.getStatusdate() +
                    " to " + newStatus.getStatusdate() +
                    " because it would orphan Danløn number for " + oldMonth.getMonth() + " " + oldMonth.getYear() + ". " +
                    "Please DELETE this status and CREATE a new status for " + newMonth.getMonth() + " " + newMonth.getYear() + " instead."
                );
            }
        }

        log.debugf("Status update validation passed for user %s", useruuid);
    }

    /**
     * Check if user has a pending salary type change that should trigger Danløn generation.
     * <p>
     * This method implements the reciprocal check for the business rule in SalaryService:
     * When a UserStatus is created/updated, check if the user recently changed from HOURLY to NORMAL
     * salary in the same month. If so, and if no Danløn number was generated yet, generate it now.
     * </p>
     * <p>
     * <b>Design Note:</b> This handles the order-dependent scenario where:
     * 1. Admin updates salary from HOURLY → NORMAL (SalaryService checks for UserStatus, finds none)
     * 2. Admin creates new UserStatus (THIS method detects the pending salary change)
     * 3. Generates Danløn number now that both conditions are met
     * </p>
     * <p>
     * <b>Conditions Required:</b>
     * - UserStatus is NOT TERMINATED or PREBOARDING
     * - User has salary record in same month with type = NORMAL
     * - Previous month's salary was type = HOURLY
     * - No Danløn history exists yet for this month with "system-salary-type-change" marker
     * </p>
     *
     * @param status The UserStatus being created or updated
     */
    private void checkForPendingSalaryTypeChange(UserStatus status) {
        // Only check for non-TERMINATED and non-PREBOARDING statuses
        if (status.getStatus() == StatusType.TERMINATED || status.getStatus() == StatusType.PREBOARDING) {
            log.debugf("Skipping salary type check for user %s - status is %s",
                    status.getUseruuid(), status.getStatus());
            return;
        }

        LocalDate monthStart = status.getStatusdate().withDayOfMonth(1);
        String useruuid = status.getUseruuid();

        log.infof("Checking for pending salary type change for user %s in month %s", useruuid, monthStart);

        // Check if Danløn was already generated for this month
        if (danlonHistoryService.hasDanlonChangedInMonthBy(useruuid, monthStart, "system-salary-type-change")) {
            log.debugf("Danløn already generated for user %s in month %s - skipping", useruuid, monthStart);
            return;
        }

        // Check if user has NORMAL salary in this month
        Optional<Salary> currentMonthSalary = Salary.find(
                "useruuid = ?1 AND activefrom = ?2 AND type = ?3",
                useruuid, monthStart, SalaryType.NORMAL
        ).firstResultOptional();

        if (currentMonthSalary.isEmpty()) {
            log.warnf("No NORMAL salary found for user %s in month %s - skipping Danløn generation. " +
                    "This may indicate a timing issue where UserStatus was created before Salary record. " +
                    "Salary type change detection will be handled by SalaryService when salary is created later.",
                    useruuid, monthStart);
            return;
        }

        // Check if previous month's salary was HOURLY
        Salary previousMonthSalary = salaryService.getUserSalaryByMonth(useruuid, monthStart.minusMonths(1));
        if (previousMonthSalary == null || previousMonthSalary.getType() != SalaryType.HOURLY) {
            log.debugf("Previous month salary for user %s was not HOURLY (was %s) - skipping",
                    useruuid, previousMonthSalary != null ? previousMonthSalary.getType() : "null");
            return;
        }

        // All conditions met - generate Danløn number
        log.infof("DETECTED pending salary type change for user %s: HOURLY → NORMAL in month %s - generating Danløn number",
                useruuid, monthStart);

        try {
            // Generate new Danløn number using the service's generation logic
            String newDanlonNumber = danlonHistoryService.generateNextDanlonNumber();

            // Create UserDanlonHistory record
            danlonHistoryService.addDanlonHistory(
                    useruuid,
                    monthStart,
                    newDanlonNumber,
                    "system-salary-type-change"
            );

            log.infof("Successfully generated Danløn number %s for user %s (triggered by UserStatus creation)",
                    newDanlonNumber, useruuid);
        } catch (IllegalArgumentException e) {
            // Duplicate history for this month - this is fine, means SalaryService already created it
            // (race condition between salary and status updates in same transaction)
            log.infof("Danløn history already exists for user %s in month %s (created by SalaryService)",
                    useruuid, monthStart);
        } catch (Exception e) {
            // Unexpected error - log but don't fail the status update
            log.errorf(e, "Unexpected error generating Danløn number for user %s", useruuid);
        }
    }

    /**
     * Check if user has company transition (TERMINATED in one company, ACTIVE in another on same date).
     * <p>
     * This method implements the business rule for automatic Danløn number generation when a user
     * transitions between companies within the organization on the same date.
     * </p>
     * <p>
     * <b>Trigger Conditions:</b>
     * - Current UserStatus is ACTIVE (or other non-TERMINATED/PREBOARDING status)
     * - Current UserStatus is NEW this month (true company transition)
     * - User has TERMINATED status in a DIFFERENT company on the EXACT SAME date
     * - No Danløn history already exists for this month with "system-company-transition" marker
     * </p>
     * <p>
     * <b>Design Note:</b> This check takes precedence over salary type change detection.
     * If both company transition AND salary type change occur in the same month, only the
     * company transition generates a Danløn number (avoiding duplicates).
     * </p>
     * <p>
     * <b>Business Justification:</b> When an employee is terminated from one company and
     * hired at another company in the organization on the same date, they need a new Danløn
     * number for the target company's payroll system.
     * </p>
     *
     * @param status The UserStatus being created or updated
     */
    private void checkForCompanyTransition(UserStatus status) {
        // Only check for qualifying statuses (not TERMINATED or PREBOARDING)
        if (status.getStatus() == StatusType.TERMINATED || status.getStatus() == StatusType.PREBOARDING) {
            log.debugf("Skipping company transition check for user %s - status is %s",
                    status.getUseruuid(), status.getStatus());
            return;
        }

        LocalDate statusDate = status.getStatusdate();
        LocalDate monthStart = statusDate.withDayOfMonth(1);
        String useruuid = status.getUseruuid();
        String currentCompanyUuid = status.getCompany() != null ? status.getCompany().getUuid() : null;

        if (currentCompanyUuid == null) {
            log.debugf("Skipping company transition check for user %s - no company association", useruuid);
            return;
        }

        log.infof("Checking for company transition for user %s in company %s on date %s",
                useruuid, currentCompanyUuid, statusDate);

        // Check if Danløn was already generated for company transition this month
        if (danlonHistoryService.hasDanlonChangedInMonthBy(useruuid, monthStart, "system-company-transition")) {
            log.debugf("Danløn already generated for company transition for user %s in month %s - skipping",
                    useruuid, monthStart);
            return;
        }

        // Check if user has TERMINATED status in DIFFERENT company on SAME date
        Optional<UserStatus> terminatedInOtherCompany = UserStatus.find(
                "useruuid = ?1 AND statusdate = ?2 AND status = ?3 AND company.uuid != ?4",
                useruuid,
                statusDate,
                StatusType.TERMINATED,
                currentCompanyUuid
        ).firstResultOptional();

        if (terminatedInOtherCompany.isEmpty()) {
            log.debugf("No TERMINATED status found in other company for user %s on date %s - skipping",
                    useruuid, statusDate);
            return;
        }

        // All conditions met - generate new Danløn number
        log.infof("DETECTED company transition for user %s: TERMINATED in company %s, ACTIVE in company %s on %s",
                useruuid,
                terminatedInOtherCompany.get().getCompany() != null ?
                    terminatedInOtherCompany.get().getCompany().getUuid() : "unknown",
                currentCompanyUuid,
                statusDate);

        try {
            // Generate new Danløn number using the service's generation logic
            String newDanlonNumber = danlonHistoryService.generateNextDanlonNumber();

            // Create UserDanlonHistory record
            danlonHistoryService.addDanlonHistory(
                    useruuid,
                    monthStart, // Active from 1st of month
                    newDanlonNumber,
                    "system-company-transition"
            );

            log.infof("Successfully generated Danløn number %s for user %s (triggered by company transition)",
                    newDanlonNumber, useruuid);
        } catch (IllegalArgumentException e) {
            // Duplicate history for this month - this is fine, means another process already created it
            log.infof("Danløn history already exists for user %s in month %s (created by another process)",
                    useruuid, monthStart);
        } catch (Exception e) {
            // Unexpected error - log but don't fail the status update
            log.errorf(e, "Unexpected error generating Danløn number for user %s (company transition)", useruuid);
        }
    }

    /**
     * Check if user is being re-employed after previous termination.
     * <p>
     * This method implements the business rule for automatic Danløn number generation when a user
     * is re-employed after being terminated (either in the same company or a different company).
     * </p>
     * <p>
     * <b>Trigger Conditions:</b>
     * - Current UserStatus is ACTIVE (or other qualifying status, not TERMINATED/PREBOARDING)
     * - User has previous TERMINATED status (any company, any earlier date)
     * - PREBOARDING statuses are ignored (treated as transition states)
     * - No Danløn history already exists for this month (any marker)
     * </p>
     * <p>
     * <b>Precedence:</b> This check runs AFTER company transition and salary type change checks.
     * If either of those rules already generated a Danløn number this month, re-employment check is skipped.
     * </p>
     * <p>
     * <b>Design Note:</b> Company transition is a specific case of re-employment (different companies on same date).
     * By running company transition first, we ensure the more specific marker ('system-company-transition')
     * is used instead of the generic re-employment marker.
     * </p>
     * <p>
     * <b>Business Justification:</b> Re-employed employees should receive a new Danløn number to
     * reflect their fresh start, whether returning to the same company or joining a different one.
     * </p>
     *
     * @param status The UserStatus being created or updated
     */
    private void checkForReEmployment(UserStatus status) {
        // Only check for qualifying statuses (not TERMINATED or PREBOARDING)
        if (status.getStatus() == StatusType.TERMINATED || status.getStatus() == StatusType.PREBOARDING) {
            log.debugf("Skipping re-employment check for user %s - status is %s",
                    status.getUseruuid(), status.getStatus());
            return;
        }

        LocalDate statusDate = status.getStatusdate();
        LocalDate monthStart = statusDate.withDayOfMonth(1);
        String useruuid = status.getUseruuid();

        log.infof("Checking for re-employment for user %s on date %s", useruuid, statusDate);

        // Check if Danløn was already generated this month (by ANY rule)
        // This ensures we don't create duplicate Danløn numbers
        if (danlonHistoryService.hasDanlonChangedInMonth(useruuid, monthStart)) {
            log.debugf("Danløn already generated for user %s in month %s - skipping re-employment check",
                    useruuid, monthStart);
            return;
        }

        // Find ANY previous TERMINATED status (any company, any date before current status)
        // Note: We explicitly query for TERMINATED only, ignoring PREBOARDING statuses
        Optional<UserStatus> previousTermination = UserStatus.find(
                "useruuid = ?1 AND status = ?2 AND statusdate < ?3 ORDER BY statusdate DESC",
                useruuid,
                StatusType.TERMINATED,
                statusDate
        ).firstResultOptional();

        if (previousTermination.isEmpty()) {
            log.debugf("No previous TERMINATED status found for user %s - skipping re-employment",
                    useruuid);
            return;
        }

        // All conditions met - user is being re-employed
        LocalDate terminationDate = previousTermination.get().getStatusdate();
        String previousCompanyUuid = previousTermination.get().getCompany() != null ?
                previousTermination.get().getCompany().getUuid() : "unknown";
        String currentCompanyUuid = status.getCompany() != null ?
                status.getCompany().getUuid() : "unknown";

        log.infof("DETECTED re-employment for user %s: TERMINATED on %s (company %s), now ACTIVE on %s (company %s)",
                useruuid, terminationDate, previousCompanyUuid, statusDate, currentCompanyUuid);

        try {
            // Generate new Danløn number using the service's generation logic
            String newDanlonNumber = danlonHistoryService.generateNextDanlonNumber();

            // Create UserDanlonHistory record
            danlonHistoryService.addDanlonHistory(
                    useruuid,
                    monthStart, // Active from 1st of month
                    newDanlonNumber,
                    "system-re-employment"
            );

            log.infof("Successfully generated Danløn number %s for user %s (triggered by re-employment)",
                    newDanlonNumber, useruuid);
        } catch (IllegalArgumentException e) {
            // Duplicate history for this month - this is fine, means another process already created it
            // (race condition between multiple status updates in same transaction)
            log.infof("Danløn history already exists for user %s in month %s (created by another process)",
                    useruuid, monthStart);
        } catch (Exception e) {
            // Unexpected error - log but don't fail the status update
            log.errorf(e, "Unexpected error generating Danløn number for user %s (re-employment)", useruuid);
        }
    }
}