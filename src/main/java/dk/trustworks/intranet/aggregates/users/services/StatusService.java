package dk.trustworks.intranet.aggregates.users.services;

import dk.trustworks.intranet.aggregates.sender.AggregateEventSender;
import dk.trustworks.intranet.aggregates.users.events.CreateUserStatusEvent;
import dk.trustworks.intranet.aggregates.users.events.DeleteUserStatusEvent;
import dk.trustworks.intranet.aggregates.users.events.UpdateUserStatusEvent;
import dk.trustworks.intranet.domain.user.entity.UserDanlonHistory;
import dk.trustworks.intranet.domain.user.entity.UserStatus;
import dk.trustworks.intranet.domain.user.service.UserDanlonHistoryService;
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
public class StatusService {

    @Inject
    AggregateEventSender aggregateEventSender;

    @Inject
    UserDanlonHistoryService danlonHistoryService;

    @Inject
    dk.trustworks.intranet.aggregates.users.danlon.DanlonEventDetector danlonEventDetector;

    @Inject
    dk.trustworks.intranet.aggregates.users.danlon.DanlonAssignmentService danlonAssignmentService;

    public List<UserStatus> listAll(String useruuid) {
        return UserStatus.findByUseruuid(useruuid);
    }

    public UserStatus getFirstEmploymentStatus(String useruuid) {
        return UserStatus.find("useruuid like ?1 order by statusdate asc limit 1", useruuid).firstResult();
    }

    public UserStatus getLatestEmploymentStatus(String useruuid) {
        List<UserStatus> userStatusList = UserStatus.find("useruuid like ?1 and statusdate <= ?2 order by statusdate desc", useruuid, LocalDate.now()).list();
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

            // Danløn lifecycle (propose-only): detect the single most-specific event for this
            // user/month/company and raise ONE proposal. No auto-mint (spec §6, AC1).
            detectAndPropose(s);
        }, () -> {
            log.info("StatusService.create -> creating status");
            log.info("status = " + status);
            status.persist();
            sendCreateEvent(status);

            // Danløn lifecycle (propose-only): see UPDATE branch.
            detectAndPropose(status);
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

    /**
     * Pure detector (spec §6, AC1): raise at most one Danløn proposal for the status's
     * user/month/company via the shared precedence cascade. Mints nothing.
     * <p>
     * Isolated in its OWN transaction and never propagates a failure — a Danløn glitch must
     * NOT roll back the status save (fixes N5, carry-forward P13). A bare try/catch would be
     * insufficient: an exception escaping the {@code @Transactional} {@code proposeIfNeeded}
     * marks the surrounding status-save transaction rollback-only. Running in a nested
     * {@code requiringNew} transaction confines any failure; the reconciliation scan (AC10)
     * re-derives and re-raises the proposal on its next run.
     */
    private void detectAndPropose(UserStatus status) {
        if (status.getStatus() == StatusType.TERMINATED || status.getStatus() == StatusType.PREBOARDING) return;
        String companyUuid = status.getCompany() != null ? status.getCompany().getUuid() : null;
        if (companyUuid == null) return;
        String useruuid = status.getUseruuid();
        LocalDate month = status.getStatusdate().withDayOfMonth(1);
        // Detect in the CALLER's transaction so just-written status/salary rows are visible
        // (a new transaction wouldn't see uncommitted rows → would miss SALARY_TYPE_CHANGE).
        var event = danlonEventDetector.detectMostSpecific(useruuid, month, companyUuid);
        if (event.isEmpty()) return;
        // Isolate only the WRITE in its own transaction so a Danløn failure can't roll back the
        // status save (carry-forward P13, fixes N5). proposeIfNeeded reads only committed danlon/
        // proposal state, so it is correct in a nested transaction; reconciliation (AC10) retries.
        try {
            QuarkusTransaction.requiringNew().run(() ->
                    danlonAssignmentService.proposeIfNeeded(useruuid, month, event.get(), companyUuid));
        } catch (RuntimeException e) {
            log.warnf(e, "Danløn propose failed for user %s month %s — status save unaffected; reconciliation (AC10) will retry",
                    useruuid, month);
        }
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

}