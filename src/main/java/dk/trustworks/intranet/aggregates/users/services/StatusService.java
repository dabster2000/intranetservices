package dk.trustworks.intranet.aggregates.users.services;

import dk.trustworks.intranet.aggregates.sender.AggregateEventSender;
import dk.trustworks.intranet.aggregates.users.events.CreateUserStatusEvent;
import dk.trustworks.intranet.aggregates.users.events.DeleteUserStatusEvent;
import dk.trustworks.intranet.aggregates.users.events.UpdateUserStatusEvent;
import dk.trustworks.intranet.domain.user.entity.UserStatus;
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

@JBossLog
@ApplicationScoped
public class StatusService {

    @Inject
    AggregateEventSender aggregateEventSender;

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
        if(status.getUuid().isEmpty()) return;
        Optional<UserStatus> existingStatus = UserStatus.findByIdOptional(status.getUuid());
        existingStatus.ifPresentOrElse(s -> {
            log.info("StatusService.create -> updating status");
            log.info("status = " + status);
            s.setStatus(status.getStatus());
            s.setStatusdate(status.getStatusdate());
            s.setType(status.getType());
            s.setCompany(status.getCompany());
            s.setTwBonusEligible(status.isTwBonusEligible());
            s.setAllocation(status.getAllocation());
            updateStatusType(s);
            sendUpdateEvent(s);
        }, () -> {
            log.info("StatusService.create -> creating status");
            log.info("status = " + status);
            status.persist();
            sendCreateEvent(status);
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
}