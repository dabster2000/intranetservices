package dk.trustworks.intranet.aggregates.users.services;

import dk.trustworks.intranet.messaging.emitters.MessageEmitter;
import dk.trustworks.intranet.userservice.model.Salary;
import dk.trustworks.intranet.userservice.model.UserStatus;
import dk.trustworks.intranet.userservice.model.enums.StatusType;

import javax.enterprise.context.ApplicationScoped;
import javax.inject.Inject;
import javax.transaction.Transactional;
import javax.validation.Valid;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;

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
    public void create(@Valid UserStatus status) {
        if(status.getUuid().isEmpty()) return;
        Optional<UserStatus> existingSalary = Salary.findByIdOptional(status.getUuid());
        existingSalary.ifPresentOrElse(s -> {
            s.setStatus(status.getStatus());
            s.setStatusdate(status.getStatusdate());
            s.setType(status.getType());
            s.setCvr(status.getCvr());
            s.setTwBonusEligible(status.isTwBonusEligible());
            s.setAllocation(status.getAllocation());
            updateStatusType(s);
        }, status::persist);
        /*
        if(status.getUuid()!=null && UserStatus.findByIdOptional(status.getUuid()).isPresent()) UserStatus.deleteById(status.getUuid());
        status.setUuid(UUID.randomUUID().toString());
        UserStatus.persist(status);
        messageEmitter.sendUserChange(status.getUseruuid());*/

    }

    private void updateStatusType(UserStatus s) {
        UserStatus.update("status = ?1, " +
                        "statusdate = ?2, " +
                        "type = ?3, " +
                        "cvr = ?4, " +
                        "twBonusEligible = ?5, " +
                        "allocation = ?6 " +
                        "WHERE uuid LIKE ?7 ",
                s.getStatus(),
                s.getStatusdate(),
                s.getType(),
                s.getCvr(),
                s.isTwBonusEligible(),
                s.getAllocation(),
                s.getUuid());
    }

    @Transactional
    public void delete(String statusuuid) {
        System.out.println("StatusService.delete");
        System.out.println("statusuuid = " + statusuuid);
        UserStatus.deleteById(statusuuid);
        //messageEmitter.sendUserChange(useruuid);
    }
}