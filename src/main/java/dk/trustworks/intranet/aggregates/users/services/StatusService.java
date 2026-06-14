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
            // persistAndFlush so a constraint violation (e.g. duplicate uq_userstatus_user_date)
            // surfaces to the caller HERE — NOT later inside detectAndPropose's best-effort
            // try/catch (Hibernate would otherwise auto-flush this INSERT on the detector's first
            // query and the catch would swallow the business-save failure → false 204).
            status.persistAndFlush();
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
        // The whole Danløn side-effect is best-effort (carry-forward P13, fixes N5). Detection runs
        // in the CALLER's transaction so just-written status/salary rows are visible (a new
        // transaction wouldn't see uncommitted rows → would miss SALARY_TYPE_CHANGE), and the WRITE
        // is isolated in a nested requiringNew transaction so its failure can't roll back the status
        // save. The whole thing is wrapped so a detection-read glitch is logged, not propagated;
        // reconciliation (AC10) re-derives and re-raises on its next run.
        try {
            var event = danlonEventDetector.detectMostSpecific(useruuid, month, companyUuid);
            if (event.isEmpty()) return;
            QuarkusTransaction.requiringNew().run(() ->
                    danlonAssignmentService.proposeIfNeeded(useruuid, month, event.get(), companyUuid));
        } catch (RuntimeException e) {
            log.warnf(e, "Danløn detect/propose failed for user %s month %s — status save best-effort; reconciliation (AC10) will retry",
                    useruuid, month);
        }
    }

    @Transactional
    @CacheInvalidateAll(cacheName = "user-cache")
    @CacheInvalidateAll(cacheName = "user-status-cache")
    @CacheInvalidateAll(cacheName = "employee-availability")
    public void delete(String statusuuid) {
        log.infof("StatusService.delete statusuuid=%s", statusuuid);
        UserStatus entity = UserStatus.<UserStatus>findById(statusuuid);
        if (entity == null) {
            // N9: never deref a null entity. Nothing to delete, nothing to close.
            log.warnf("StatusService.delete: status %s not found — nothing to do", statusuuid);
            return;
        }

        // Forward-only Danløn (spec §7, fixes N1): never hard-delete a minted number. If a
        // system-minted OPEN row exists for the deleted status's month, raise a CLOSE proposal.
        // Reconciliation withdraws it if the employment remains/again valid.
        if (entity.getStatus() == StatusType.TERMINATED || entity.getStatus() == StatusType.ACTIVE) {
            LocalDate month = entity.getStatusdate().withDayOfMonth(1);
            UserDanlonHistory row = UserDanlonHistory.findRowForMonth(entity.getUseruuid(), month);
            if (row != null && !row.isClosed() && row.getEventType() != null) {
                danlonAssignmentService.proposeClose(row.getUuid(),
                        "Triggering " + entity.getStatus() + " status for " + month + " was deleted");
            }
        }

        UserStatus.deleteById(statusuuid);
        aggregateEventSender.handleEvent(new DeleteUserStatusEvent(entity.getUseruuid(), entity));
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