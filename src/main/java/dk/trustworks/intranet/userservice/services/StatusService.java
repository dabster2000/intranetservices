package dk.trustworks.intranet.userservice.services;

import dk.trustworks.intranet.aggregateservices.messaging.MessageEmitter;
import dk.trustworks.intranet.userservice.model.UserStatus;
import dk.trustworks.intranet.userservice.model.enums.StatusType;

import javax.enterprise.context.ApplicationScoped;
import javax.inject.Inject;
import javax.transaction.Transactional;
import javax.validation.Valid;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

@ApplicationScoped
public class StatusService {

    @Inject
    MessageEmitter messageEmitter;

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
    public void create(String useruuid, @Valid UserStatus status) {
        if(status.getUuid()!=null && UserStatus.findByIdOptional(status.getUuid()).isPresent()) UserStatus.deleteById(status.getUuid());
        status.setUuid(UUID.randomUUID().toString());
        status.setUseruuid(useruuid);
        UserStatus.persist(status);

        messageEmitter.sendUserChange(useruuid);
    }

    @Transactional
    public void delete(String useruuid, String statusuuid) {
        UserStatus.delete("uuid", statusuuid);

        messageEmitter.sendUserChange(useruuid);
    }
}